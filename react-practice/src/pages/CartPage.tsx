import { Link } from 'react-router-dom';
import { useCart } from '../store/CartContext';

export function CartPage() {
  const { lines, total, setQty, remove, clear } = useCart();

  if (lines.length === 0) {
    return (
      <p>
        Your cart is empty. <Link to="/">Browse products</Link>
      </p>
    );
  }

  return (
    <>
      <h1>Cart</h1>
      <ul className="product-list">
        {lines.map(({ product, qty }) => (
          <li key={product.id} className="card">
            <div>
              <Link to={`/products/${product.id}`}>{product.name}</Link>
              <p className="meta">${product.price} each</p>
            </div>
            <div className="qty">
              <input
                type="number"
                min={0}
                value={qty}
                onChange={(event) => setQty(product.id, Number(event.target.value))}
              />
              <button type="button" onClick={() => remove(product.id)}>
                Remove
              </button>
            </div>
          </li>
        ))}
      </ul>
      <p className="total">Total: ${total}</p>
      <button type="button" onClick={clear}>
        Clear cart
      </button>
    </>
  );
}
