import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { fetchProduct } from '../api/products';
import { useCart } from '../store/CartContext';
import type { Product } from '../types';

interface Loaded {
  id: string;
  product: Product | null;
}

export function ProductDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { add } = useCart();
  const [loaded, setLoaded] = useState<Loaded | null>(null);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;

    fetchProduct(id).then((result) => {
      if (!cancelled) setLoaded({ id, product: result ?? null });
    });

    return () => {
      cancelled = true;
    };
  }, [id]);

  // Derived during render: whatever we hold is stale until its id matches the URL.
  if (!loaded || loaded.id !== id) return <p>Loading…</p>;

  const product = loaded.product;
  if (!product) {
    return (
      <p>
        No product with id “{id}”. <Link to="/">Back to products</Link>
      </p>
    );
  }

  return (
    <article className="detail">
      <Link to="/">← All products</Link>
      <h1>{product.name}</h1>
      <p className="meta">
        {product.category} · ${product.price} · ★ {product.rating}
      </p>
      <p>{product.description}</p>
      <button type="button" disabled={!product.inStock} onClick={() => add(product)}>
        {product.inStock ? 'Add to cart' : 'Out of stock'}
      </button>
    </article>
  );
}
