package app.dispofold.solver;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningSolutionMetaModel;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningVariableMetaModel;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.builtin.Moves;

/**
 * A duty made longer in one step: a cell's duty moves to a longer shift of its area that covers it, and a duty of
 * the same area on the same day that the longer shift covers as well goes (two short duties become one long one).
 * A single change cannot do that: lengthening first overstaffs, dropping first leaves a gap. Without such a second
 * duty the move only lengthens. Random selection only, with the solver's random generator.
 */
public class MergeMoves implements MoveIteratorFactory<Roster, Move<Roster>> {

    static final PlanningVariableMetaModel<Roster, DutyCell, ShiftOption> SHIFT = PlanningSolutionMetaModel
            .of(Roster.class, DutyCell.class).genuineEntity(DutyCell.class).basicVariable("shift", ShiftOption.class);

    /** Tries per move before the iterator gives a plain lengthening or the first cell back unchanged. */
    private static final int TRIES = 20;

    @Override
    public long getSize(ScoreDirector<Roster> scoreDirector) {
        return scoreDirector.getWorkingSolution().getCells().size();
    }

    @Override
    public Iterator<Move<Roster>> createOriginalMoveIterator(ScoreDirector<Roster> scoreDirector) {
        throw new UnsupportedOperationException("random selection only");
    }

    @Override
    public Iterator<Move<Roster>> createRandomMoveIterator(ScoreDirector<Roster> scoreDirector,
            RandomGenerator random) {
        List<DutyCell> movable = new ArrayList<>();
        Map<LocalDate, List<DutyCell>> byDay = new HashMap<>();
        for (DutyCell c : scoreDirector.getWorkingSolution().getCells()) {
            if (!c.isPinned()) {
                movable.add(c);
                byDay.computeIfAbsent(c.getDate(), d -> new ArrayList<>()).add(c);
            }
        }
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return !movable.isEmpty();
            }

            @Override
            public Move<Roster> next() {
                Move<Roster> move = null;
                for (int i = 0; i < TRIES && move == null; i++) {
                    move = merge(movable.get(random.nextInt(movable.size())), byDay, random);
                }
                if (move == null) {
                    // nothing to lengthen found: a move that changes nothing (the forager does not accept it)
                    DutyCell c = movable.get(random.nextInt(movable.size()));
                    move = Moves.change(SHIFT, c, c.getShift());
                }
                return move;
            }
        };
    }

    /** The move for this cell, or null when its duty has no longer shift that covers it. */
    static Move<Roster> merge(DutyCell cell, Map<LocalDate, List<DutyCell>> byDay, RandomGenerator random) {
        ShiftOption now = cell.getShift();
        if (now == null || now.isCompRest() || !cell.isInMonth()) {
            return null;
        }
        long start = cell.startOf(now);
        long end = cell.endOf(now);
        List<ShiftOption> longer = new ArrayList<>();
        for (ShiftOption o : cell.getOptions()) {
            if (!o.isCompRest() && now.getAreaId().equals(o.getAreaId())
                    && o.getDurationMinutes() > now.getDurationMinutes() && cell.startOf(o) <= start
                    && cell.endOf(o) >= end) {
                longer.add(o);
            }
        }
        if (longer.isEmpty()) {
            return null;
        }
        ShiftOption to = longer.get(random.nextInt(longer.size()));
        long from = cell.startOf(to);
        long until = cell.endOf(to);
        List<DutyCell> covered = new ArrayList<>();
        for (DutyCell other : byDay.get(cell.getDate())) {
            ShiftOption s = other.getShift();
            if (other != cell && s != null && !s.isCompRest() && now.getAreaId().equals(s.getAreaId())
                    && other.getStart() >= from && other.getEnd() <= until) {
                covered.add(other);
            }
        }
        Move<Roster> lengthen = Moves.change(SHIFT, cell, to);
        if (covered.isEmpty()) {
            return lengthen;
        }
        return Moves.compose(lengthen, Moves.change(SHIFT, covered.get(random.nextInt(covered.size())), null));
    }
}
