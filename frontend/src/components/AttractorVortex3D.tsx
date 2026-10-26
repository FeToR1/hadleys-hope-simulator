import { useEffect, useRef, type JSX } from 'react';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import type { AttractorMode, PhasePoint } from '../domain/types';
import { phaseBounds, projectPhasePoint } from '../domain/attractor';

const MAX_POINTS = 3_000;
const VIEW_WIDTH = 420;
const VIEW_HEIGHT = 300;

function pointColor(point: PhasePoint, index: number, total: number, mode: AttractorMode): THREE.Color {
  const progress = total <= 1 ? 0 : index / (total - 1);
  const stable = new THREE.Color('#48d597');
  const chaos = new THREE.Color('#ff9d59');
  const collapse = new THREE.Color('#ff304f');
  const color = stable.clone().lerp(chaos, progress);
  if (point.x < 0 || mode === 'collapse' && progress > 0.72) color.lerp(collapse, Math.max(0.65, progress));
  else if (mode === 'chaotic' && progress > 0.45) color.lerp(chaos, progress);
  return color;
}

export function AttractorVortex3D({ history, mode }: { history: readonly PhasePoint[]; mode: AttractorMode }): JSX.Element {
  const hostRef = useRef<HTMLDivElement>(null);
  const geometryRef = useRef<THREE.BufferGeometry | null>(null);
  const positionAttributeRef = useRef<THREE.BufferAttribute | null>(null);
  const colorAttributeRef = useRef<THREE.BufferAttribute | null>(null);
  const explosionGeometryRef = useRef<THREE.BufferGeometry | null>(null);
  const latestGeometryRef = useRef<THREE.BufferGeometry | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (host === null) return;

    const scene = new THREE.Scene();
    scene.background = new THREE.Color('#020609');
    const camera = new THREE.PerspectiveCamera(45, VIEW_WIDTH / VIEW_HEIGHT, 0.1, 2_000);
    camera.position.set(28, 24, 32);
    camera.lookAt(0, 0, 0);

    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setSize(VIEW_WIDTH, VIEW_HEIGHT, false);
    host.replaceChildren(renderer.domElement);

    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.target.set(0, 0, 0);
    controls.update();

    const geometry = new THREE.BufferGeometry();
    const positions = new Float32Array(MAX_POINTS * 3);
    const colors = new Float32Array(MAX_POINTS * 3);
    const positionAttribute = new THREE.BufferAttribute(positions, 3);
    const colorAttribute = new THREE.BufferAttribute(colors, 3);
    geometry.setAttribute('position', positionAttribute);
    geometry.setAttribute('color', colorAttribute);
    geometry.setDrawRange(0, 0);
    const material = new THREE.LineBasicMaterial({ vertexColors: true, transparent: true, opacity: 0.95 });
    const line = new THREE.Line(geometry, material);
    scene.add(line);

    const explosionGeometry = new THREE.BufferGeometry();
    explosionGeometry.setAttribute('position', new THREE.BufferAttribute(new Float32Array(MAX_POINTS * 3), 3));
    explosionGeometry.setDrawRange(0, 0);
    const explosionMaterial = new THREE.PointsMaterial({ color: '#ff9d59', size: 0.7 });
    scene.add(new THREE.Points(explosionGeometry, explosionMaterial));
    const latestGeometry = new THREE.BufferGeometry();
    latestGeometry.setAttribute('position', new THREE.BufferAttribute(new Float32Array(3), 3));
    latestGeometry.setDrawRange(0, 0);
    const latestMaterial = new THREE.PointsMaterial({ color: '#e2eefb', size: 0.4 });
    scene.add(new THREE.Points(latestGeometry, latestMaterial));

    const grid = new THREE.GridHelper(24, 12, '#1d493d', '#102a27');
    grid.position.y = -10;
    scene.add(grid);
    const axes = new THREE.AxesHelper(20);
    axes.position.set(-10, -10, -10);
    scene.add(axes);
    geometryRef.current = geometry;
    positionAttributeRef.current = positionAttribute;
    colorAttributeRef.current = colorAttribute;
    explosionGeometryRef.current = explosionGeometry;
    latestGeometryRef.current = latestGeometry;

    const resize = (): void => {
      const width = Math.max(1, host.clientWidth || VIEW_WIDTH);
      const height = Math.max(1, host.clientHeight || VIEW_HEIGHT);
      renderer.setSize(width, height, false);
      camera.aspect = width / height;
      camera.updateProjectionMatrix();
    };
    const resizeObserver = new ResizeObserver(resize);
    resizeObserver.observe(host);
    resize();

    let frame = 0;
    const render = (): void => {
      controls.update();
      renderer.render(scene, camera);
      frame = requestAnimationFrame(render);
    };
    frame = requestAnimationFrame(render);

    return () => {
      cancelAnimationFrame(frame);
      resizeObserver.disconnect();
      controls.dispose();
      geometry.dispose();
      material.dispose();
      explosionGeometry.dispose();
      explosionMaterial.dispose();
      latestGeometry.dispose();
      latestMaterial.dispose();
      axes.geometry.dispose();
      (Array.isArray(axes.material) ? axes.material : [axes.material]).forEach((axisMaterial) => axisMaterial.dispose());
      grid.geometry.dispose();
      (Array.isArray(grid.material) ? grid.material : [grid.material]).forEach((gridMaterial) => gridMaterial.dispose());
      renderer.dispose();
      renderer.forceContextLoss();
      host.replaceChildren();
      geometryRef.current = null;
      positionAttributeRef.current = null;
      colorAttributeRef.current = null;
      explosionGeometryRef.current = null;
      latestGeometryRef.current = null;
    };
  }, []);

  useEffect(() => {
    const geometry = geometryRef.current;
    const positionAttribute = positionAttributeRef.current;
    const colorAttribute = colorAttributeRef.current;
    if (geometry === null || positionAttribute === null || colorAttribute === null) return;

    const points = history.slice(-MAX_POINTS);
    const bounds = phaseBounds(points);
    const positions = positionAttribute.array as Float32Array;
    const colors = colorAttribute.array as Float32Array;
    for (let index = 0; index < points.length; index += 1) {
      const point = points[index];
      const projected = projectPhasePoint(point, bounds);
      const offset = index * 3;
      positions[offset] = projected.x * 20 - 10;
      positions[offset + 1] = projected.y * 20 - 10;
      positions[offset + 2] = projected.z * 20 - 10;
      const color = pointColor(point, index, points.length, mode);
      colors[offset] = color.r;
      colors[offset + 1] = color.g;
      colors[offset + 2] = color.b;
    }
    positionAttribute.needsUpdate = true;
    colorAttribute.needsUpdate = true;
    geometry.setDrawRange(0, points.length);
    geometry.computeBoundingSphere();

    const explosionGeometry = explosionGeometryRef.current;
    if (explosionGeometry !== null) {
      const attribute = explosionGeometry.getAttribute('position') as THREE.BufferAttribute;
      let count = 0;
      points.forEach((point, index) => {
        if (!point.reactorExplosion) return;
        attribute.setXYZ(count++, positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2]);
      });
      attribute.needsUpdate = true;
      explosionGeometry.setDrawRange(0, count);
      explosionGeometry.computeBoundingSphere();
    }
    const latestGeometry = latestGeometryRef.current;
    if (latestGeometry !== null) {
      const attribute = latestGeometry.getAttribute('position') as THREE.BufferAttribute;
      if (points.length > 0) {
        const offset = (points.length - 1) * 3;
        attribute.setXYZ(0, positions[offset], positions[offset + 1], positions[offset + 2]);
      }
      attribute.needsUpdate = true;
      latestGeometry.setDrawRange(0, points.length > 0 ? 1 : 0);
      latestGeometry.computeBoundingSphere();
    }
  }, [history, mode]);

  return <div><div ref={hostRef} className="attractor-vortex" aria-label="3D фазовый портрет: температура, потребление энергии и баланс" />
    <p className="attractor-axis-help">3D: X — температура · Y — потребление · Z — баланс. Оси нормированы по истории; оранжевые точки — взрывы.</p>
  </div>;
}
