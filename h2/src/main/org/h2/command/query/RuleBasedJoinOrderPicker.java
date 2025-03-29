package org.h2.command.query;

import org.h2.engine.SessionLocal;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionVisitor;
import org.h2.expression.condition.Comparison;
import org.h2.table.TableFilter;

import java.util.*;

/**
 * Determines the best join order by following rules rather than considering every possible permutation.
 */
public class RuleBasedJoinOrderPicker {
    final SessionLocal session;
    final TableFilter[] filters;

    public RuleBasedJoinOrderPicker(SessionLocal session, TableFilter[] filters) {
        this.session = session;
        this.filters = filters;
    }

    public TableFilter[] bestOrder() {
        List<TableFilter> remaining = new ArrayList<>(Arrays.asList(filters));
        List<TableFilter> result = new ArrayList<>();
        Map<TableFilter, Set<TableFilter>> joinGraph = new HashMap<>();
        for (TableFilter tf : filters) {
            Expression fullCondition = tf.getFullCondition();
            if (fullCondition != null) {
                extractJoinPairs(fullCondition, joinGraph);
            }
        }

        // Pick the table with smallest row count as the first table
        TableFilter start = remaining.stream()
                .min(Comparator.comparingLong(f -> f.getTable().getRowCountApproximation(session)))
                .orElseThrow(() -> new RuntimeException("No tables to join"));

        result.add(start);
        remaining.remove(start);

        // add joinable tables
        while (!remaining.isEmpty()) {
            TableFilter next = null;
            long minRows = Long.MAX_VALUE;

            for (TableFilter candidate : remaining) {
                if (canJoinWithAny(result, candidate, joinGraph)) {
                    long rows = candidate.getTable().getRowCountApproximation(session);
                    if (rows < minRows) {
                        minRows = rows;
                        next = candidate;
                    }
                }
            }

            if (next == null) {
                throw new RuntimeException("Cannot join remaining tables without creating a cartesian product");
            }

            result.add(next);
            remaining.remove(next);
        }

        return result.toArray(new TableFilter[0]);
    }

    private void extractJoinPairs(Expression expr, Map<TableFilter, Set<TableFilter>> graph) {
        if (expr instanceof Comparison) {
            Expression left = expr.getSubexpression(0);
            Expression right = expr.getSubexpression(1);
            TableFilter leftFilter = getFilterForExpr(left);
            TableFilter rightFilter = getFilterForExpr(right);
            if (leftFilter != null && rightFilter != null && leftFilter != rightFilter) {
                addJoinEdge(graph, leftFilter, rightFilter);
                addJoinEdge(graph, rightFilter, leftFilter);
            }
        }
        for (int i = 0; i < expr.getSubexpressionCount(); i++) {
            extractJoinPairs(expr.getSubexpression(i), graph);
        }
    }

    private TableFilter getFilterForExpr(Expression expr) {
        for (TableFilter tf : filters) {
            ExpressionVisitor visitor = ExpressionVisitor.getNotFromResolverVisitor(tf);
            if (!expr.isEverything(visitor)) {
                return tf;
            }
        }
        return null;
    }

    private void addJoinEdge(Map<TableFilter, Set<TableFilter>> graph, TableFilter a, TableFilter b) {
        graph.computeIfAbsent(a, k -> new HashSet<>()).add(b);
    }

    private boolean canJoinWithAny(List<TableFilter> joined, TableFilter candidate, Map<TableFilter, Set<TableFilter>> graph) {
        for (TableFilter already : joined) {
            Set<TableFilter> neighbors = graph.get(already);
            if (neighbors != null && neighbors.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
