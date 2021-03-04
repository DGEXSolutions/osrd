package fr.sncf.osrd.infra.railscript;

import fr.sncf.osrd.infra.InvalidInfraException;

public class RSExprVisitor {

    public void visit(RSExpr.Or expr) throws InvalidInfraException {
        for (var e : expr.expressions)
            e.accept(this);
    }

    public void visit(RSExpr.And expr) throws InvalidInfraException {
        for (var e : expr.expressions)
            e.accept(this);
    }

    public void visit(RSExpr.Not expr) throws InvalidInfraException {
        expr.expr.accept(this);
    }

    public void visit(RSExpr.True expr) {
    }

    public void visit(RSExpr.False expr) {
    }

    public void visit(RSExpr.AspectSet expr) throws InvalidInfraException {
        for (var condition : expr.conditions)
            condition.accept(this);
    }

    public void visit(RSExpr.SignalRef expr) throws InvalidInfraException {
    }

    public void visit(RSExpr.RouteRef expr) throws InvalidInfraException {
    }

    /** Visit method */
    public void visit(RSExpr.If<?> expr) throws InvalidInfraException {
        expr.ifExpr.accept(this);
        expr.thenExpr.accept(this);
        expr.elseExpr.accept(this);
    }

    public void visit(RSExpr.Call<?> expr) throws InvalidInfraException {
        for (var arg : expr.arguments)
            arg.accept(this);
    }

    /** Visit method */
    public void visit(RSExpr.EnumMatch<?, ?> expr) throws InvalidInfraException {
        expr.expr.accept(this);
        for (var branch : expr.branches)
            branch.accept(this);
    }

    public void visit(RSExpr.ArgumentRef<?> expr) {
    }

    public void visit(RSExpr.SignalAspectCheck expr) throws InvalidInfraException {
        expr.signalExpr.accept(this);
    }

    public void visit(RSExpr.RouteStateCheck expr) throws InvalidInfraException {
        expr.routeExpr.accept(this);
    }

    public void visit(RSExpr.AspectSetContains expr) throws InvalidInfraException {
        expr.expr.accept(this);
    }
}