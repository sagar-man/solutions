export type Category = 'keyboard' | 'mouse' | 'monitor' | 'audio';

export interface Product {
  id: string;
  name: string;
  category: Category;
  price: number;
  rating: number;
  inStock: boolean;
  description: string;
}

export interface CartLine {
  product: Product;
  qty: number;
}
