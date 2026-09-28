import { useEffect, useRef } from 'react';
import * as THREE from 'three';
import type { PhasePoint } from '../domain/types';

interface Props {
  history: readonly PhasePoint[];
  mode: string;
}

export function Attractor3DGraph({ history, mode }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const sceneRef = useRef<THREE.Scene | null>(null);
  const cameraRef = useRef<THREE.PerspectiveCamera | null>(null);
  const rendererRef = useRef<THREE.WebGLRenderer | null>(null);
  const geometryRef = useRef<THREE.BufferGeometry | null>(null);
  const pointsObjRef = useRef<THREE.Points | null>(null);
  const requestRef = useRef<number>(0);

  useEffect(() => {
    if (!containerRef.current) return;

    const width = containerRef.current.clientWidth;
    const height = 150;

    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x1a1a1a); // Dark background

    const camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 1000);
    camera.position.set(200, 200, 200);
    camera.lookAt(0, 0, 0);

    const renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setSize(width, height);
    containerRef.current.appendChild(renderer.domElement);

    const geometry = new THREE.BufferGeometry();
    const material = new THREE.PointsMaterial({
      color: 0x00ffcc,
      size: 3,
      sizeAttenuation: true
    });

    const points = new THREE.Points(geometry, material);
    scene.add(points);

    // Grid helpers
    scene.add(new THREE.GridHelper(400, 20, 0x444444, 0x222222));

    sceneRef.current = scene;
    cameraRef.current = camera;
    rendererRef.current = renderer;
    geometryRef.current = geometry;
    pointsObjRef.current = points;

    const animate = () => {
      requestRef.current = requestAnimationFrame(animate);
      if (sceneRef.current && cameraRef.current && pointsObjRef.current) {
        // Slowly rotate scene
        sceneRef.current.rotation.y += 0.005;
        rendererRef.current?.render(sceneRef.current, cameraRef.current);
      }
    };
    animate();

    return () => {
      if (requestRef.current) cancelAnimationFrame(requestRef.current);
      if (rendererRef.current && containerRef.current) {
        containerRef.current.removeChild(rendererRef.current.domElement);
        rendererRef.current.dispose();
      }
      geometryRef.current?.dispose();
    };
  }, []);

  useEffect(() => {
    if (!geometryRef.current || !history.length || !pointsObjRef.current) return;

    // Update color based on mode
    const material = pointsObjRef.current.material as THREE.PointsMaterial;
    if (mode === 'stationary') material.color.setHex(0xaaaaaa);
    else if (mode === 'periodic') material.color.setHex(0x00ffcc);
    else if (mode === 'chaotic') material.color.setHex(0xffaa00);
    else if (mode === 'collapse') material.color.setHex(0xff0000);

    const positions = new Float32Array(history.length * 3);

    // Center the points somewhat around their mean or max to fit camera
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity, minZ = Infinity, maxZ = -Infinity;

    for (const p of history) {
        if(p.x < minX) minX = p.x;
        if(p.x > maxX) maxX = p.x;
        if(p.y < minY) minY = p.y;
        if(p.y > maxY) maxY = p.y;
        if(p.z < minZ) minZ = p.z;
        if(p.z > maxZ) maxZ = p.z;
    }

    const rangeX = maxX - minX || 1;
    const rangeY = maxY - minY || 1;
    const rangeZ = maxZ - minZ || 1;
    const range = Math.max(rangeX, rangeY, rangeZ);
    const scale = 150 / range;

    const cx = (minX + maxX) / 2;
    const cy = (minY + maxY) / 2;
    const cz = (minZ + maxZ) / 2;

    for (let i = 0; i < history.length; i++) {
      const p = history[i];
      positions[i * 3] = (p.x - cx) * scale;
      positions[i * 3 + 1] = (p.y - cy) * scale;
      positions[i * 3 + 2] = (p.z - cz) * scale;
    }

    geometryRef.current.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geometryRef.current.attributes.position.needsUpdate = true;

  }, [history, mode]);

  return <div ref={containerRef} style={{ width: '100%', height: '150px', marginBottom: '10px', overflow: 'hidden' }} />;
}
