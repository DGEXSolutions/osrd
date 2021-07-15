package fr.sncf.osrd.infra.railscript;

import fr.sncf.osrd.infra.InvalidInfraException;

public class RSExprVisitor {

    /** Visit method */
    public void visit(RSExpr.Or expr) throws InvalidInfraException {
        for (var e : expr.expressions)
            e.accept(this);
    }

    /** Visit method */
    public void visit(RSExpr.And expr) throws InvalidInfraException {
        for (var e : expr.expressions)
            e.accept(this);
    }

    /** Visit method */
    public void visit(RSExpr.Not expr) throws InvalidInfraException {
        expr.expr.accept(this);
    }

    /** Visit method */
    public void visit(RSExpr.True expr) {
    }

    /** Visit method */
    public void visit(RSExpr.False expr) {
    }

    /** Visit method */
    public void visit(RSExpr.AspectSet expr) throws InvalidInfraException {
        for (var condition : expr.conditions) {
            if (condition != null)
                condition.accept(this);
        }
    }

    /** Visit method */
    public void visit(RSExpr.SignalRef expr) throws InvalidInfraException {
    }

    /** Visit method */
    public void visit(RSExpr.RouteRef expr) throws InvalidInfraException {
    }

    /** Visit method */
    public void visit(RSExpr.SwitchRef expr) throws InvalidInfraException {
    }

    /** Visit method */
    public void visit(RSExpr.If<?> expr) throws InvalidInfraException {
        expr.ifExpr.accept(this);
        expr.thenExpr.accept(this);
        expr.elseExpr.accept(this);
    }

    /** Visit method */
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

    /** Visit method */
    public void visit(RSExpr.ArgumentRef<?> expr) {
    }

    /** Visit method */
    public void visit(RSExpr.Delay<?> expr) throws InvalidInfraException {
        expr.expr.accept(this);
    }

    /** Visit method */
    public void visit(RSExpr.SignalAspectCheck expr) throws InvalidInfraException {
        expr.signalExpr.accept(this);
    }

    /** Visit method */
    public void visit(RSExpr.RouteStateCheck expr) throws InvalidInfraException {
        expr.routeExpr.accept(this);
    }

    /** Visit method */
    public void visit(RSExpr.AspectSetContains expr) throws InvalidInfraException {
        expr.expr.accept(this);
    }

    /** Visit method */
    public void visit(RSExpr.OptionalMatch<?> expr) throws InvalidInfraException {
        expr.caseSome.accept(this);
        expr.caseNone.accept(this);
        expr.expr.accept(this);
    }

    /** Visit method */
    public void visit(RSExpr.OptionalMatchRef<?> tOptionalMatchRef) {
    }

    /** Visit method */
    public void visit(RSExpr.ReservedRoute reservedRoute) throws InvalidInfraException {
        reservedRoute.signal.accept(this);
    }

    /** Visit method */
    public void visit(RSExpr.NextSignal nextSignal) throws InvalidInfraException {
        nextSignal.signal.accept(this);
        nextSignal.route.accept(this);
    }

    /** Visit method */
    public void visit(RSExpr.IsIncomingRouteCBTC expr) throws InvalidInfraException {
        expr.routeExpr.accept(this);
    }
}
