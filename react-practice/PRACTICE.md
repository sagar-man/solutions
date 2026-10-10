# 45-Minute React Pairing Exercise — Prep Kit

This folder is a **working reference implementation** of exactly the three things the
invite names: a client-side router, state that survives navigation, and a local JSON
file rendered as a list.

Use it in this order:

1. **Read it once** (15 min) — `src/` is small on purpose.
2. **Rebuild it from an empty folder, timed** (45 min) — this is the actual practice.
3. **Repeat against the mock specs below** until the plumbing is muscle memory.

Reading code is not practice. Typing it under a clock is.

---

## Part 1 — Setup you should do on your own machine (do this before the call)

```bash
npm create vite@latest my-app -- --template react-ts
cd my-app
npm install
npm install react-router-dom
npm run dev
```

That is the whole setup. Four commands, under two minutes. Verify before the interview:

- [ ] `npm run dev` serves on localhost and hot-reloads an edit to `App.tsx`
- [ ] `npm run build` passes (this runs `tsc -b`, so it catches type errors)
- [ ] your editor shows TypeScript errors inline, and auto-import works
- [ ] React DevTools installed in the browser
- [ ] you can create a file and import it without fighting paths

**Have this project already created and running when the call starts.** Then delete the
boilerplate (`App.css`, `src/assets/`, the counter in `App.tsx`) so you begin from a
blank page rather than Vite's demo.

One more thing worth doing: keep a scratch file of the snippets in Part 2 open in
another tab. "My own notes are fine" — so make notes worth having.

---

## Part 2 — The three concepts, minimal form

### 2.1 Router

The whole setup, from memory:

```tsx
import { BrowserRouter, Routes, Route } from 'react-router-dom';

<BrowserRouter>
  <Routes>
    <Route element={<Layout />}>              {/* shared chrome, renders <Outlet /> */}
      <Route index element={<ListPage />} />   {/* "/" */}
      <Route path="items/:id" element={<DetailPage />} />
      <Route path="cart" element={<CartPage />} />
      <Route path="*" element={<NotFound />} />
    </Route>
  </Routes>
</BrowserRouter>
```

Four hooks cover nearly everything you'll be asked for:

| Hook | Use |
| --- | --- |
| `useParams<{ id: string }>()` | read `:id` from the URL (**always `string \| undefined`**) |
| `useNavigate()` | navigate from an event handler: `navigate('/cart')`, `navigate(-1)` |
| `useSearchParams()` | read/write `?q=…` — filter and sort state belongs here |
| `useLocation()` | current path, e.g. for a "redirect back after login" flow |

And two components: `<Link to="…">` for navigation, `<NavLink>` when you want an
`active` class for free.

Traps, in the order people hit them:

- **`<a href>` instead of `<Link>`** → full page reload, state wiped. Interviewers notice.
- **Layout route with no `<Outlet />`** → child pages render nothing. Blank page, no error.
- **Params are strings.** `useParams().id` compared against a numeric `item.id` fails
  silently. Either keep ids as strings in your data (this repo does) or `Number(id)`.
- **`*` route last**, or the catch-all swallows real routes.
- **Nothing renders at all?** You forgot to wrap in `<BrowserRouter>`.

### 2.2 State that persists across navigation

This is the one they are really testing. The point: **when you navigate, the old page
unmounts and its `useState` dies.** So state that must outlive a page cannot live
inside that page. It has to live above the router, or in the URL, or on disk.

Four options, and knowing *when* to pick each is the actual signal:

| Where | Good for | Cost |
| --- | --- | --- |
| `useState` in the page | one page's own UI (open accordion, draft input) | dies on navigate |
| **URL** (`useSearchParams`) | filters, search, sort, pagination | strings only |
| **Context + `useReducer`** above the router | cart, selection, session, cross-page flows | re-renders consumers |
| `localStorage` | survives a full refresh | serialization, needs a sync point |

The real answer is usually *combine them*: filters in the URL, domain state in context,
context mirrored to `localStorage`. That is what `src/store/CartContext.tsx` does, and
it's what you should narrate out loud when you reach this part of the spec.

The pattern, condensed — this is the snippet to be able to type without thinking:

```tsx
const CartContext = createContext<CartContextValue | null>(null);

export function CartProvider({ children }: { children: ReactNode }) {
  const [lines, dispatch] = useReducer(cartReducer, undefined, loadInitialState);

  useEffect(() => {
    localStorage.setItem(KEY, JSON.stringify(lines));   // mirror on every change
  }, [lines]);

  const value = useMemo(() => ({
    lines,
    itemCount: lines.reduce((n, l) => n + l.qty, 0),     // derive, never store
    add: (p: Product) => dispatch({ type: 'add', product: p }),
  }), [lines]);

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart() {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error('useCart must be used inside a <CartProvider>');
  return ctx;
}
```

Five things in there are deliberate, and each is worth a sentence out loud:

1. **`createContext<T | null>(null)` + a throwing hook.** No fake default object to
   silently paper over a missing provider, and consumers get a non-nullable type.
2. **The provider goes outside `<BrowserRouter>`** (see `src/App.tsx`) — or at least
   above the `<Routes>`. Inside a route element, it unmounts with the page and you've
   built the bug you were asked to avoid.
3. **Lazy `useReducer` initializer** (third arg) so `localStorage` is parsed once, not
   on every render. `useReducer(r, loadInitialState())` would run it every render.
4. **`useMemo` on the value** so consumers don't re-render on unrelated parent renders.
   Say why you did it; don't memoize everything reflexively.
5. **Totals and counts are derived, not stored.** Two sources of truth for a number is
   the classic cart bug.

If asked "would you reach for Redux / Zustand?" — a good answer is: not for this. Context
plus a reducer has no dependency and covers it; I'd move to Zustand when I want
selector-level subscriptions to stop re-rendering every consumer, or Redux Toolkit on a
large team that wants the devtools and conventions. Have the opinion ready, briefly.

### 2.3 Loading local JSON and rendering a list

Two ways, and **ask which they want** — it changes the code:

**(a) Static import** — bundled at build time, typed, no loading state:

```ts
import raw from './data/products.json';
const products = raw as Product[];   // JSON widens `category` to `string`
```

**(b) Behind a fake async boundary** — what this repo does, because it's what a real app
looks like and it lets you show loading and error states:

```ts
export function fetchProducts(): Promise<Product[]> {
  return new Promise((r) => setTimeout(() => r(products), 400));
}
```

Then one hook owns the loading triad (`src/hooks/useProducts.ts`):

```ts
const [products, setProducts] = useState<Product[]>([]);
const [loading, setLoading] = useState(true);   // start true: you ARE loading
const [error, setError] = useState<string | null>(null);

useEffect(() => {
  let cancelled = false;                         // don't write after unmount
  fetchProducts()
    .then((r) => { if (!cancelled) { setProducts(r); setError(null); } })
    .catch((e: unknown) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Failed'); })
    .finally(() => { if (!cancelled) setLoading(false); });
  return () => { cancelled = true; };
}, []);
```

If the file lives in `public/` instead, it's `fetch('/products.json')` and you must
check `res.ok` yourself — `fetch` does not reject on a 404.

Rendering traps:

- **`key={index}`.** Use the stable id. If they ask why: on insert or reorder, React
  reuses the wrong DOM node and local state lands on the wrong row.
- **Filter during render, don't mirror it into state.** A `filteredItems` state plus an
  effect to keep it in sync is the most common thing I'd push back on in a review.
  `const visible = products.filter(...)` recomputes for free.
- **Handle the empty result**, not just the empty load. "No products match those
  filters" is a different message from "Loading…".
- **`loading` before `error` before empty before the list** — order your early returns.

---

## Part 3 — How to spend the 45 minutes

They said: read or listen to the whole spec before typing. Take that literally.

| Minutes | What |
| --- | --- |
| 0–4 | Listen to the whole spec. Write down the **pages**, the **data shape**, and **what state crosses pages**. Ask your questions now. |
| 4–7 | Types + the JSON file. `types.ts` first — it makes everything after it autocomplete. |
| 7–12 | Routes and empty page components. Get navigation clicking between stubs. |
| 12–22 | Data loading + the list page. First real screen. |
| 22–30 | Detail page via `:id`. Now the router is doing real work. |
| 30–40 | The cross-page state (cart / selection / favorites). The thing being tested. |
| 40–45 | Edge cases out loud: unknown id, empty cart, out of stock, refresh. Fix what's cheap, **name what you'd do next**. |

Rules of thumb for the clock:

- **Vertical slices, not layers.** One page working end to end beats four half-built
  pages. A thing they can click is worth more than a thing you can describe.
- **Stub, then fill.** Four empty page components wired to routes in minute 10 means you
  can never run out of runway and have nothing to show.
- **Don't style.** A `<ul>` and real spacing is enough. If you have spare minutes at the
  end, spend them on an edge case, not CSS.
- **Narrate decisions, not keystrokes.** "I'm putting the filter in the URL so it
  survives a refresh" is signal. "Now I type `const`" is noise.
- **Say the tradeoff you're skipping.** "I'd debounce this search in a real app; it's 8
  items so I won't" tells them you saw it. Silence looks like you missed it.
- **If you get stuck, say what you're stuck on.** It's a pairing exercise. Thinking out
  loud *is* the deliverable.

Questions worth asking at minute 2 (pick the ones the spec left open):

- Should filter/search state survive a refresh, or just navigation? *(URL vs context)*
- Is the JSON a static import, or should I simulate async with loading and error states?
- Do you want the cross-page state to survive a full page refresh too? *(localStorage)*
- Is there a design to follow, or is unstyled semantic markup fine?
- Should I write tests, or is working behavior the priority for 45 minutes?
- Any library preference — React Router? Any state library you'd want to see?

That last set is not filler. "Asking clarifying questions is part of what we're looking
at" is in your invite twice.

---

## Part 4 — Drills

Do these in a throwaway folder, from empty, against the clock. Delete and repeat.

**Drill 1 — Plumbing, 10 minutes.** Vite + TS + router. Three routes, a layout with nav,
a catch-all. No data. Goal: zero thinking about setup during the real thing.

**Drill 2 — List and detail, 15 minutes.** 8-item JSON file → list → click → detail via
`:id` → back. Handle an id that doesn't exist. Goal: `useParams` and lookup without
hesitating.

**Drill 3 — Cross-page state, 15 minutes.** Add a cart or a favorites set with a count
badge in the nav. Verify by hand: add on the list page, navigate to detail, navigate to
cart — count must be right. Goal: the Context + reducer snippet from memory.

**Drill 4 — URL state, 10 minutes.** Search box + category filter in `useSearchParams`.
Verify: filter, click into a detail page, press back — your filter is still applied.
Goal: knowing the difference between URL state and component state in your hands, not
just in your head.

**Drill 5 — The whole thing, 45 minutes.** Pick a mock spec below, read it once, set a
timer, build it while talking out loud to an empty room. Yes, out loud — the talking is
the part that degrades under time pressure.

Afterwards, grade yourself on one question: **could someone click through it?** Not "is
it clean", not "is it finished".

---

## Part 5 — Mock specs

Give yourself a single read, then start the timer.

**Spec A — Issue tracker.** `issues.json` with id, title, status (`open`/`closed`),
priority, assignee, body. `/` lists issues with a status filter; `/issues/:id` shows one
with its full body; `/starred` lists starred issues. A star toggle appears on both the
list and the detail page, and the nav shows a starred count. The status filter must
survive navigating into an issue and back.

**Spec B — Recipe box.** `recipes.json` with id, name, minutes, tags, ingredients.
`/` lists recipes with a tag filter; `/recipes/:id` shows ingredients; `/plan` shows a
meal plan you've added recipes to, with a total cook time. Adding the same recipe twice
should bump a count, not duplicate a row. Plan survives a refresh.

**Spec C — Course catalog.** `courses.json` with id, title, credits, department,
prerequisites (array of course ids). `/` lists courses by department;
`/courses/:id` shows details and renders prerequisites as links to those courses;
`/schedule` shows selected courses with total credits and refuses to add a course past
18 credits. Selection persists across pages.

Each one is deliberately the same skeleton — list, detail-by-id, a third page fed by
cross-page state. That skeleton is what they're hiring for. Spec C's
prerequisite-links-to-other-courses is the interesting wrinkle: same route, different
param, and your detail page has to refetch when only the `:id` changes. (That's why
`src/pages/ProductDetailPage.tsx` keys its loaded data by id — go read why.)

---

## Part 6 — This repo, file by file

```
src/
  types.ts                     Product, CartLine, Category — write this first
  data/products.json           8 items, the local "API"
  api/products.ts              fake async boundary + the JSON-widening cast
  hooks/useProducts.ts         loading / error / data, with cancellation
  store/CartContext.tsx        Context + useReducer + localStorage  ← the hard part
  components/Layout.tsx        nav, cart badge, <Outlet />
  pages/ProductListPage.tsx    list + URL-backed search and filter
  pages/ProductDetailPage.tsx  useParams, refetch on id change, unknown id
  pages/CartPage.tsx           quantities, total, clear
  pages/NotFoundPage.tsx       the * route
  App.tsx                      provider outside router, route table
```

Run it:

```bash
npm install
npm run dev      # http://localhost:5173
npm run build    # typecheck + production build
npm run lint
```

`npm run lint` reports one warning, on purpose: oxlint's fast-refresh rule doesn't like
`CartContext.tsx` exporting both a component (`CartProvider`) and a hook (`useCart`).
The production fix is to split the context and hook into their own module. In a
45-minute exercise, one file is the right call — but **know the answer**, because "why
is that warning there" is exactly the kind of thing that gets asked.

---

## Part 7 — What they're actually grading

Mostly not the code. From the way the invite is written, they're watching:

- Did you **read the whole spec** before typing, or start coding paragraph one?
- Did you **ask about the ambiguity**, or guess and build the wrong thing quietly?
- Can you **explain a choice** — why the URL, why context, why that key?
- Do you know **where state lives and why**? This is the one real technical question
  behind the whole exercise.
- When something breaks, do you **debug methodically** or start shotgunning changes?
- Is there **something working at minute 45**?

Things that read badly and cost nothing to avoid: silence while typing, `any` sprinkled
to make errors go away, styling before behavior, and building something they didn't ask
for because you find it more interesting.

One last thing: it's a pairing exercise, so the engineer is a resource, not an examiner.
"Would you rather I put filters in the URL or in context?" is a perfectly good thing to
say out loud at minute 20.
