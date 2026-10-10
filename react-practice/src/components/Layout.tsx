import { NavLink, Outlet } from 'react-router-dom';
import { useCart } from '../store/CartContext';

export function Layout() {
  const { itemCount } = useCart();

  return (
    <div className="app">
      <header className="topbar">
        <strong>Desk Shop</strong>
        <nav>
          <NavLink to="/">Products</NavLink>
          <NavLink to="/cart">Cart{itemCount > 0 && <span className="badge">{itemCount}</span>}</NavLink>
        </nav>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  );
}
