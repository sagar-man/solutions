import rawProducts from '../data/products.json';
import type { Product } from '../types';

// JSON imports are inferred as wide types (`category: string`), so we assert
// once here and the rest of the app works with the real `Product` type.
const products = rawProducts as Product[];

const LATENCY_MS = 400;

function delay<T>(value: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), LATENCY_MS));
}

export function fetchProducts(): Promise<Product[]> {
  return delay(products);
}

export function fetchProduct(id: string): Promise<Product | undefined> {
  return delay(products.find((product) => product.id === id));
}
