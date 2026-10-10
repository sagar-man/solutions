import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  type ReactNode,
} from 'react';
import type { CartLine, Product } from '../types';

type CartState = CartLine[];

type CartAction =
  | { type: 'add'; product: Product }
  | { type: 'remove'; id: string }
  | { type: 'setQty'; id: string; qty: number }
  | { type: 'clear' };

const STORAGE_KEY = 'react-practice:cart';

function cartReducer(state: CartState, action: CartAction): CartState {
  switch (action.type) {
    case 'add': {
      const existing = state.find((line) => line.product.id === action.product.id);
      if (!existing) return [...state, { product: action.product, qty: 1 }];
      return state.map((line) =>
        line.product.id === action.product.id ? { ...line, qty: line.qty + 1 } : line,
      );
    }
    case 'remove':
      return state.filter((line) => line.product.id !== action.id);
    case 'setQty':
      if (action.qty < 1) return state.filter((line) => line.product.id !== action.id);
      return state.map((line) =>
        line.product.id === action.id ? { ...line, qty: action.qty } : line,
      );
    case 'clear':
      return [];
  }
}

function loadInitialState(): CartState {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored ? (JSON.parse(stored) as CartState) : [];
  } catch {
    return [];
  }
}

interface CartContextValue {
  lines: CartState;
  itemCount: number;
  total: number;
  add: (product: Product) => void;
  remove: (id: string) => void;
  setQty: (id: string, qty: number) => void;
  clear: () => void;
}

const CartContext = createContext<CartContextValue | null>(null);

export function CartProvider({ children }: { children: ReactNode }) {
  // Third argument runs lazily, so localStorage is read once instead of on every render.
  const [lines, dispatch] = useReducer(cartReducer, undefined, loadInitialState);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(lines));
  }, [lines]);

  const value = useMemo<CartContextValue>(
    () => ({
      lines,
      itemCount: lines.reduce((sum, line) => sum + line.qty, 0),
      total: lines.reduce((sum, line) => sum + line.qty * line.product.price, 0),
      add: (product) => dispatch({ type: 'add', product }),
      remove: (id) => dispatch({ type: 'remove', id }),
      setQty: (id, qty) => dispatch({ type: 'setQty', id, qty }),
      clear: () => dispatch({ type: 'clear' }),
    }),
    [lines],
  );

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartContextValue {
  const context = useContext(CartContext);
  if (!context) throw new Error('useCart must be used inside a <CartProvider>');
  return context;
}
