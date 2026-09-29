import { useEffect, useRef, type JSX } from 'react';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import type { AttractorMode, PhasePoint } from '../domain/types';

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
  const lineRef = useRef<THREE.Line<THREE.BufferGeometry, THREE.LineBasicMaterial> | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (host === null) return;

    const scene = new THREE.Scene();
    scene.background = new THREE.Color('#020609');
    const camera = new THREE.PerspectiveCamera(45, VIEW_WIDTH / VIEW_HEIGHT, 0.1, 2_000);
    camera.position.set(28, 24, 32);
    camera.lookAt(0, 35, 0);

    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setSize(VIEW_WIDTH, VIEW_HEIGHT, false);
    host.replaceChildren(renderer.domElement);

    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.target.set(0, 35, 0);
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

    const grid = new THREE.GridHelper(24, 12, '#1d493d', '#102a27');
    grid.position.y = 0;
    scene.add(grid);
    geometryRef.current = geometry;
    positionAttributeRef.current = positionAttribute;
    colorAttributeRef.current = colorAttribute;
    lineRef.current = line;

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
      grid.geometry.dispose();
      (Array.isArray(grid.material) ? grid.material : [grid.material]).forEach((gridMaterial) => gridMaterial.dispose());
      renderer.dispose();
      renderer.forceContextLoss();
      host.replaceChildren();
      geometryRef.current = null;
      positionAttributeRef.current = null;
      colorAttributeRef.current = null;
      lineRef.current = null;
    };
  }, []);

  useEffect(() => {
    const geometry = geometryRef.current;
    const positionAttribute = positionAttributeRef.current;
    const colorAttribute = colorAttributeRef.current;
    if (geometry === null || positionAttribute === null || colorAttribute === null) return;

    const points = history.slice(-MAX_POINTS);
    const positions = positionAttribute.array as Float32Array;
    const colors = colorAttribute.array as Float32Array;
    for (let index = 0; index < points.length; index += 1) {
      const point = points[index];
      const previous = points[index - 1];
      const powerDelta = previous === undefined ? 0 : point.y - previous.y;
      const noise = Math.sin(point.tickId * 0.73) * Math.min(2.5, Math.abs(powerDelta) * 0.15);
      const radius = 5 + Math.max(0, 20 - point.x) * 2;
      const angle = point.tickId * 0.1;
      const offset = index * 3;
      positions[offset] = radius * Math.cos(angle) + noise;
      positions[offset + 1] = point.tickId * 0.05;
      positions[offset + 2] = radius * Math.sin(angle) + Math.cos(point.tickId * 0.41) * noise;
      const color = pointColor(point, index, points.length, mode);
      colors[offset] = color.r;
      colors[offset + 1] = color.g;
      colors[offset + 2] = color.b;
    }
    positionAttribute.needsUpdate = true;
    colorAttribute.needsUpdate = true;
    geometry.setDrawRange(0, points.length);
    geometry.computeBoundingSphere();
  }, [history, mode]);

  return <div ref={hostRef} className="attractor-vortex" aria-label="3D вихрь фазового пространства" />;
}
