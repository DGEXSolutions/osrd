package fr.sncf.osrd.infra.railscript;

import fr.sncf.osrd.infra.Infra;
import fr.sncf.osrd.infra.InvalidInfraException;
import fr.sncf.osrd.infra.railscript.value.RSType;
import fr.sncf.osrd.infra.railscript.value.RSValue;

import java.io.PrintStream;
import java.util.HashMap;

/** This class allow to pretty print signaling functions and expressions */
public class PrettyPrinter extends RSExprVisitor {
    private final int tabstop = 4;
    private int curtab = 0;
    private RSFunction<?> currentFct;
    public final PrintStream out;

    public PrettyPrinter(PrintStream out) {
        this.out = out;
    }

    /** Pretty print all script functions of an infra */
    public void print(Infra infra) throws InvalidInfraException {
        // Search for all functions
        var functionsFinder = new FunctionsFinder();

        for (var signal: infra.signals)
            signal.expr.accept(functionsFinder);

        out.print("// SCRIPT FUNCTIONS\n\n");
        // Pretty print functions found
        for (var function : functionsFinder.functions.values()) {
            print(function);
            out.print("\n");
        }

        out.print("\n// SIGNAL EXPRESSIONS\n\n");
        for (var signal: infra.signals) {
            out.printf("%s: ", signal.id);
            signal.expr.accept(this);
            out.print("\n\n");
        }
    }

    /** Pretty print a function */
    public void print(RSFunction<?> fct) throws InvalidInfraException {
        currentFct = fct;
        out.printf("fn %s(", fct.functionName);
        // Arguments
        if (fct.argNames.length > 0) {
            out.print("\n");
            inc();
            inctab();
            for (var i = 0; i < fct.argNames.length; i++) {
                out.printf("%s: ", fct.argNames[i]);
                print(fct.argTypes[i]);
                if (i < fct.argNames.length - 1) {
                    out.print(",\n");
                    tab();
                }
            }
            out.print("\n");
            dec();
            dectab();
        }
        // Return value
        out.print(") -> ");
        print(fct.returnsType);
        out.print(" {\n");
        // Body
        inctab();
        fct.body.accept(this);
        out.print("\n");
        dectab();
        out.print("}\n");
        currentFct = null;
    }

    private void print(RSType type) {
        out.print(type);
    }

    /** Display the tab matching the current indentation */
    private void tab() {
        out.print(" ".repeat(curtab));
    }

    /** Increase one level of indentation **/
    private void inc() {
        curtab += tabstop;
    }

    /** Increase one level of indentation and display the current tab **/
    private void inctab() {
        inc();
        tab();
    }

    /** Decrease one level of indentation **/
    private void dec() {
        curtab -= tabstop;
        assert curtab >= 0;
    }

    /** Decrease one level of indentation and display the current tab **/
    private void dectab() {
        dec();
        tab();
    }

    @Override
    public void visit(RSExpr.True expr) {
        out.print("true");
    }

    @Override
    public void visit(RSExpr.False expr) {
        out.print("false");
    }

    @Override
    public void visit(RSExpr.SignalRef expr) {
        out.printf("\"%s\"", expr.signalName);
    }

    @Override
    public void visit(RSExpr.SwitchRef expr) throws InvalidInfraException {
        out.printf("\"%s\"", expr.switchName);
    }

    @Override
    public void visit(RSExpr.RouteRef expr) {
        out.printf("\"%s\"", expr.routeName);
    }

    @Override
    public void visit(RSExpr.Or expr) throws InvalidInfraException {
        if (expr.expressions.length == 1) {
            expr.expressions[0].accept(this);
            return;
        }
        out.print("(");
        expr.expressions[0].accept(this);
        for (var i = 1; i < expr.expressions.length; i++) {
            out.print(" or ");
            expr.expressions[i].accept(this);
        }
        out.print(")");
    }

    @Override
    public void visit(RSExpr.OptionalMatch<?> expr) throws InvalidInfraException {
        out.print("match ");
        expr.expr.accept(this);
        out.print(" {\n");
        inctab();

        out.print("Some(");
        out.print(expr.name);
        out.print("): \n");
        inctab();
        expr.caseSome.accept(this);
        out.print("\n");
        dectab();

        out.print("None: ");
        out.print("\n");
        inctab();
        expr.caseNone.accept(this);
        dectab();
        out.print("\n");
        dectab();
        out.print("}\n");
    }

    @Override
    public void visit(RSExpr.OptionalMatchRef<?> expr) {
        out.print(expr.name);
    }

    @Override
    public void visit(RSExpr.And expr) throws InvalidInfraException {
        expr.expressions[0].accept(this);
        for (var i = 1; i < expr.expressions.length; i++) {
            out.print(" and ");
            expr.expressions[i].accept(this);
        }
    }

    @Override
    public void visit(RSExpr.Not expr) throws InvalidInfraException {
        out.print("not (");
        expr.expr.accept(this);
        out.print(")");
    }

    @Override
    public void visit(RSExpr.AspectSet expr) throws InvalidInfraException {
        out.print("AspectSet {\n");
        inctab();
        for (var i = 0; i < expr.conditions.length; i++) {
            out.printf("%s", expr.aspects[i].id);
            if (expr.conditions[i] != null) {
                out.print(" if ");
                expr.conditions[i].accept(this);
            }
            if (i < expr.conditions.length - 1) {
                out.print(",");
                out.print("\n");
                tab();
            }
        }
        out.print("\n");
        dectab();
        out.print("}");
    }

    @Override
    public void visit(RSExpr.If<?> expr) throws InvalidInfraException {
        out.print("if ");
        expr.ifExpr.accept(this);
        out.print(" {\n");
        inctab();
        expr.thenExpr.accept(this);
        out.print("\n");
        dectab();
        out.print("} else {\n");
        inctab();
        expr.elseExpr.accept(this);
        out.print("\n");
        dectab();
        out.print("}");
    }

    @Override
    public void visit(RSExpr.Call<?> expr) throws InvalidInfraException {
        out.printf("%s(", expr.function.functionName);
        for (var i = 0; i < expr.arguments.length - 1; i++) {
            expr.arguments[i].accept(this);
            out.print(", ");
        }
        if (expr.arguments.length > 0)
            expr.arguments[expr.arguments.length - 1].accept(this);
        out.print(")");
    }

    @Override
    public void visit(RSExpr.EnumMatch<?, ?> expr) throws InvalidInfraException {
        var argTypes = currentFct == null ? new RSType[0] : currentFct.argTypes;
        var enumCond = expr.expr.getType(argTypes).enumClass;
        if (enumCond == null)
            throw new InvalidInfraException("EnumMatch: the type of the condition expr isn't matchable");
        var enumValues = enumCond.getEnumConstants();

        out.print("match ");
        expr.expr.accept(this);
        out.print(" {\n");
        inc();
        for (var i = 0; i < expr.branches.length; i++) {
            tab();
            out.printf("%s: ", enumValues[i].name());
            expr.branches[i].accept(this);
            if (i < expr.branches.length - 1)
                out.print(",");
            out.print("\n");
        }
        dectab();
        out.print("}");
    }

    @Override
    public void visit(RSExpr.ArgumentRef<?> expr) {
        out.printf("%s", currentFct.argNames[expr.slotIndex]);
    }

    @Override
    public void visit(RSExpr.SignalAspectCheck expr) throws InvalidInfraException {
        out.print("signal_has_aspect(");
        expr.signalExpr.accept(this);
        out.printf(", %s)", expr.aspect.id);
    }

    @Override
    public void visit(RSExpr.RouteStateCheck expr) throws InvalidInfraException {
        out.print("route_has_state(");
        expr.routeExpr.accept(this);
        out.printf(", %s)", expr.status);
    }

    @Override
    public void visit(RSExpr.AspectSetContains expr) throws InvalidInfraException {
        out.print("aspect_set_contains(");
        expr.expr.accept(this);
        out.printf(", %s)", expr.aspect.id);
    }

    @Override
    public void visit(RSExpr.Delay<?> expr) throws InvalidInfraException {
        out.print("delay(");
        expr.expr.accept(this);
        out.printf(", %f)", expr.duration);
    }

    private static class FunctionsFinder extends RSExprVisitor {
        public final HashMap<String, RSFunction<? extends RSValue>> functions = new HashMap<>();

        @Override
        public void visit(RSExpr.Call<?> expr) throws InvalidInfraException {
            functions.put(expr.function.functionName, expr.function);
            expr.function.body.accept(this);
        }
    }

    @Override
    public void visit(RSExpr.ReservedRoute reservedRoute) throws InvalidInfraException {
        out.print("reserved_route(");
        reservedRoute.signal.accept(this);
        out.print(")");
    }

    @Override
    public void visit(RSExpr.NextSignal nextSignal) throws InvalidInfraException {
        out.print("next_signal(");
        nextSignal.route.accept(this);
        out.print(", ");
        nextSignal.signal.accept(this);
        out.print(")");
    }

    @Override
    public void visit(RSExpr.IsIncomingRouteCBTC expr) throws InvalidInfraException {
        out.print("is_incoming_route_cbtc(");
        expr.routeExpr.accept(this);
        out.print(")");
    }
}
