"""Grids headless (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §4): a page's grid run by DataCube's own code in V8
-- ``grid.view()``, what the grid shows as DataCube computes it; ``grid.relation``, its rows at their finest level as
Pure, as Create a Class makes them; ``grid.to_pandas()``, every one of those rows from the frames' database -- each
checked against pandas computing the same, in the same cell, with no browser."""

import unittest
from decimal import Decimal

import pandas as pd

import legend_lite as ll

TRADES = pd.DataFrame({
    'region': ['EMEA', 'EMEA', 'AMER', 'APAC', 'AMER', 'EMEA'],
    'desk': ['Rates', 'FX', 'Rates', 'FX', 'FX', 'Rates'],
    'notional': [100.0, 250.5, 75.25, 10.0, 40.0, 1.5],
    'qty': [1, 2, 3, 4, 5, 6],
})


def column(view, name):
    """A view's column of values, by its name."""
    at = [c['name'] for c in view.columns].index(name)
    return [row[at] for row in view.rows]


def groups(view, measure):
    """A grouped view's level-1 groups: label -> the measure's value."""
    labels, values = column(view, '__tree'), column(view, measure)
    return {labels[i]: values[i] for i, t in enumerate(view.tree) if t['level'] == 1}


class Grids(unittest.TestCase):
    def setUp(self):
        self.page = ll.Page('Headless trades')

    def test_a_grouped_grid_shows_what_pandas_computes(self):
        grid = self.page.grid(TRADES, name='trades_grouped', rows=['region'], measures={'notional': 'sum', 'qty': 'sum'})
        view = grid.view()
        # as on screen: the groups' labels first, then the columns, each aggregated
        self.assertEqual([c['name'] for c in view.columns], ['__tree', 'desk', 'notional', 'qty'])
        expected = TRADES.groupby('region')[['notional', 'qty']].sum()
        self.assertEqual(groups(view, 'qty'), expected.qty.to_dict())
        for region, notional in groups(view, 'notional').items():
            self.assertIsInstance(notional, float)
            self.assertAlmostEqual(notional, expected.notional[region])
        # a column not measured shows its one value, or none when the group holds several (uniqueValueOnly)
        desks = {r: (d.iloc[0] if d.nunique() == 1 else None) for r, d in TRADES.groupby('region').desk}
        self.assertEqual(groups(view, 'desk'), desks)
        self.assertTrue(all(t['group'] and not t['expanded'] for t in view.tree))
        self.assertEqual(view.truncated, [])
        self.assertIn('groupBy', view.query)
        self.assertIn('GROUP BY', view.sql.upper())

    def test_a_filter_and_a_calculated_column_reach_the_relation_and_its_rows(self):
        grid = self.page.grid(TRADES, name='trades_shaped')
        grid.filter(('region', 'equal', 'EMEA'))
        grid.calculate('big', '$x.notional->toOne() > 50')
        grid.group('desk')
        relation = grid.relation
        # the finest level the grid shows: its rows filtered and extended, grouped by its innermost group, as Create a
        # Class makes a class of them
        for part in ("filter(x|$x.region == 'EMEA')", 'big:x|$x.notional->toOne() > 50', 'groupBy(~[desk]'):
            self.assertIn(part, relation)
        rows = grid.to_pandas().set_index('desk').sort_index()
        emea = TRADES[TRADES.region == 'EMEA'].assign(big=lambda d: d.notional > 50)
        one = lambda d: d.iloc[0] if d.nunique() == 1 else None  # noqa: E731 -- uniqueValueOnly
        expected = emea.groupby('desk').agg(region=('region', one), notional=('notional', 'sum'), qty=('qty', 'sum'),
                                            big=('big', one)).sort_index()
        self.assertEqual(sorted(rows.columns), sorted(expected.columns))
        pd.testing.assert_frame_equal(rows[list(expected.columns)], expected, check_dtype=False)

    def test_a_flat_grid_shows_its_rows_and_its_relation_is_them(self):
        grid = self.page.grid(TRADES, name='trades_flat')
        view = grid.view()
        self.assertEqual([c['name'] for c in view.columns], list(TRADES.columns))
        self.assertEqual(sorted(column(view, 'qty')), sorted(TRADES.qty))
        self.assertNotIn('groupBy', grid.relation)
        self.assertEqual(view.to_pandas().shape, TRADES.shape)
        pd.testing.assert_frame_equal(grid.to_pandas().sort_values('qty').reset_index(drop=True), TRADES, check_dtype=False)

    def test_a_grid_changed_in_python_is_viewed_as_it_is_now(self):
        grid = self.page.grid(TRADES, name='trades_changing', rows=['desk'], measures={'qty': 'sum'})
        self.assertEqual(groups(grid.view(), 'qty'), TRADES.groupby('desk').qty.sum().to_dict())
        grid.filter(('qty', 'greaterThan', 2))
        self.assertEqual(groups(grid.view(), 'qty'), TRADES[TRADES.qty > 2].groupby('desk').qty.sum().to_dict())

    def test_its_configuration_is_folded_in_as_on_screen(self):
        # the row cap, a hidden column and a column's aggregate: what the tab folds into its query and lays out
        flat = self.page.grid(TRADES, name='trades_capped')
        flat.configure({'maxRows': 2, 'columns': {'desk': {'hidden': True}}})
        view = flat.view()
        self.assertEqual(len(view.rows), 2, 'the row cap, as on screen')
        self.assertEqual(view.total_rows, len(TRADES), 'and how many rows there are in all')
        self.assertNotIn('desk', [c['name'] for c in view.columns], 'a hidden column, not shown')
        grouped = self.page.grid(TRADES, name='trades_aggregated', rows=['desk'])
        grouped.configure({'columns': {'qty': {'aggregateFn': 'max'}}})
        self.assertEqual(groups(grouped.view(), 'qty'), TRADES.groupby('desk').qty.max().to_dict())
        self.assertIn('max()', grouped.relation)
        rows = grouped.to_pandas().set_index('desk').qty.to_dict()
        self.assertEqual(rows, TRADES.groupby('desk').qty.max().to_dict())

    def test_a_grid_read_while_another_thread_registers_frames_does_not_wait_on_it(self):
        # the session's lock and the frames' lock, taken in one order by both: a grid read beside frames registered
        import threading
        grid = self.page.grid(TRADES, name='trades_threads', rows=['desk'], measures={'qty': 'sum'})
        failures: list[BaseException] = []

        def register() -> None:
            try:
                for i in range(20):
                    self.page.grid(TRADES, name=f'trades_beside_{i}')
            except BaseException as e:  # noqa: BLE001 -- reported below
                failures.append(e)
        other = threading.Thread(target=register)
        other.start()
        for _ in range(20):
            self.assertEqual(groups(grid.view(), 'qty'), TRADES.groupby('desk').qty.sum().to_dict())
        other.join(timeout=60)
        self.assertFalse(other.is_alive(), 'the registering thread finished')
        self.assertEqual(failures, [])

    def test_called_inside_a_running_event_loop_as_a_notebook_cell_is(self):
        # Jupyter runs a cell inside its own asyncio loop: the headless engine runs on a loop of its own, on its own
        # thread, so a synchronous call from inside a running loop answers, and never touches that loop
        import asyncio
        grid = self.page.grid(TRADES, name='trades_in_a_loop', rows=['desk'], measures={'qty': 'sum'})

        async def cell():
            running = asyncio.get_running_loop()
            view = grid.view()
            self.assertIs(asyncio.get_running_loop(), running)
            return groups(view, 'qty')
        self.assertEqual(asyncio.run(cell()), TRADES.groupby('desk').qty.sum().to_dict())

    def test_numbers_cross_exactly(self):
        exact = pd.DataFrame({'id': [9007199254740993, 1], 'x': [float('nan'), float('inf')]})
        view = self.page.grid(exact, name='exact_numbers').view()
        ids = column(view, 'id')
        self.assertEqual(sorted(ids), [1, 9007199254740993], 'an integer past 2^53, exact')
        # pandas' NaN is a missing value as the frame is registered (Arrow reads it so), infinity a float
        self.assertEqual(sorted(column(view, 'x'), key=str), [None, float('inf')])


if __name__ == '__main__':
    unittest.main()
