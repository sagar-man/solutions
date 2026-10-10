import { Link, useSearchParams } from 'react-router-dom';
import { useProducts } from '../hooks/useProducts';
import { useCart } from '../store/CartContext';
import type { Category } from '../types';

const CATEGORIES: Category[] = ['keyboard', 'mouse', 'monitor', 'audio'];

export function ProductListPage() {
  const { products, loading, error } = useProducts();
  const { add } = useCart();

  // Filters live in the URL, so they survive navigation, refresh, and sharing.
  const [params, setParams] = useSearchParams();
  const query = params.get('q') ?? '';
  const category = params.get('category') ?? '';

  const visible = products.filter((product) => {
    const matchesQuery = product.name.toLowerCase().includes(query.toLowerCase());
    const matchesCategory = !category || product.category === category;
    return matchesQuery && matchesCategory;
  });

  if (loading) return <p>Loading products…</p>;
  if (error) return <p role="alert">{error}</p>;

  return (
    <>
      <div className="filters">
        <input
          type="search"
          placeholder="Search products"
          value={query}
          onChange={(event) => {
            const next = new URLSearchParams(params);
            if (event.target.value) next.set('q', event.target.value);
            else next.delete('q');
            setParams(next, { replace: true });
          }}
        />
        <select
          value={category}
          onChange={(event) => {
            const next = new URLSearchParams(params);
            if (event.target.value) next.set('category', event.target.value);
            else next.delete('category');
            setParams(next, { replace: true });
          }}
        >
          <option value="">All categories</option>
          {CATEGORIES.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      </div>

      {visible.length === 0 ? (
        <p>No products match those filters.</p>
      ) : (
        <ul className="product-list">
          {visible.map((product) => (
            <li key={product.id} className="card">
              <div>
                <Link to={`/products/${product.id}`}>{product.name}</Link>
                <p className="meta">
                  {product.category} · ${product.price} · ★ {product.rating}
                  {!product.inStock && ' · out of stock'}
                </p>
              </div>
              <button type="button" disabled={!product.inStock} onClick={() => add(product)}>
                Add to cart
              </button>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
