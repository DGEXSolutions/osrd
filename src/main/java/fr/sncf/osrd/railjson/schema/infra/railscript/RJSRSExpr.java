package fr.sncf.osrd.railjson.schema.infra.railscript;

import com.squareup.moshi.Json;
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory;
import fr.sncf.osrd.railjson.schema.common.ID;
import fr.sncf.osrd.railjson.schema.common.Identified;
import fr.sncf.osrd.railjson.schema.infra.RJSRoute;
import fr.sncf.osrd.railjson.schema.infra.RJSSwitch;
import fr.sncf.osrd.railjson.schema.infra.signaling.RJSAspect;
import fr.sncf.osrd.railjson.schema.infra.trackobjects.RJSSignal;

import java.util.Map;

/**
 * <h1>SignalScript</h1>
 * <p>SignalScript has support for:</p>
 * <ul>
 *     <li>
 *         <p>data types:</p>
 *         <ul>
 *             <li>boolean</li>
 *             <li>AspectSet</li>
 *             <li>Signal (matchable)</li>
 *             <li>Route (matchable)</li>
 *             <li>Switch (matchable)</li>
 *         </ul>
 *     </li>
 *     <li>functions and function calls, in a strictly non recursive fashion
 *         (functions can only call already declared functions)</li>
 *     <li>purely functional conditions (mandatory else)</li>
 *     <li>a set comprehension construct for building AspectSet</li>
 *     <li>pattern matching on enum values</li>
 * </ul>
 *
 * <p>Some caveats:</p>
 * <ul>
 *     <li>no loops</li>
 *     <li>no variables</li>
 *     <li>no recursion</li>
 * </ul>
 *
 * <h2>Source code example</h2>
 * <b>No parser for this language is planned yet, it's only there to help understand things better</b>
 *
 * <pre>
 * {@code
 *
 * fn sncf_filter(aspects: AspectSet) -> AspectSet {
 *     if aspect_set_contains(aspects, RED) {
 *         AspectSet{RED}
 *     } else if aspect_set_contains(aspects, YELLOW) {
 *         AspectSet{YELLOW}
 *     } else {
 *         aspects
 *     }
 * }
 *
 * fn bal3_line_signal(
 *         master_signal: Signal,
 *         route: Route,
 * ) -> AspectSet {
 *     sncf_filter(AspectSet{
 *         RED if route_has_state(route, OCCUPIED),
 *         YELLOW if signal_has_aspect(master_signal, RED),
 *         GREEN
 *     })
 * }
 *
 * fn switch_bal3_signal(
 *         switch: Switch,
 *         left_master_signal: Signal,
 *         right_master_signal: Signal,
 *         left_route: Route,
 *         right_route: Route
 * ) -> AspectSet {
 *     match switch {
 *         LEFT: bal3_line_signal(left_master_signal, left_route)
 *         RIGHT: bal3_line_signal(right_master_signal, right_route)
 *     }
 * }
 *
 * Signal {
 *     expr: bal3_line_signal(master_signal: "test_signal_foo", route: "test_route_bar")
 * }
 *
 * }
 * </pre>
 */
public abstract class RJSRSExpr {
    public static final PolymorphicJsonAdapterFactory<RJSRSExpr> adapter = (
            PolymorphicJsonAdapterFactory.of(RJSRSExpr.class, "type")
                    // boolean operators
                    .withSubtype(Or.class, "or")
                    .withSubtype(And.class, "and")
                    .withSubtype(Not.class, "not")
                    // value constructors
                    .withSubtype(True.class, "true")
                    .withSubtype(False.class, "false")
                    .withSubtype(AspectSet.class, "aspect_set")
                    .withSubtype(SignalRef.class, "signal")
                    .withSubtype(RouteRef.class, "route")
                    .withSubtype(SwitchRef.class, "switch")
                    // control flow
                    .withSubtype(If.class, "condition")
                    .withSubtype(Call.class, "call")
                    .withSubtype(EnumMatch.class, "match")
                    .withSubtype(OptionalMatch.class, "optional_match")
                    // references
                    .withSubtype(ArgumentRef.class, "argument_ref")
                    .withSubtype(OptionalMatchRef.class, "optional_match_ref")
                    // primitives
                    .withSubtype(Delay.class, "delay")
                    .withSubtype(SignalAspectCheck.class, "signal_has_aspect")
                    .withSubtype(RouteStateCheck.class, "route_has_state")
                    .withSubtype(AspectSetContains.class, "aspect_set_contains")
                    .withSubtype(ReservedRoute.class, "reserved_route")
                    .withSubtype(NextSignal.class, "next_signal")
                    .withSubtype(IsIncomingRouteCBTC.class, "is_incoming_route_cbtc")
    );

    // region BOOLEAN_LOGIC

    public abstract static class InfixOp extends RJSRSExpr {
        public RJSRSExpr[] exprs;

        public InfixOp(RJSRSExpr[] exprs) {
            this.exprs = exprs;
        }
    }

    public static final class Or extends InfixOp {
        public Or(RJSRSExpr[] exprs) {
            super(exprs);
        }
    }

    public static final class And extends InfixOp {
        public And(RJSRSExpr[] exprs) {
            super(exprs);
        }
    }

    public static final class Not extends RJSRSExpr {
        public RJSRSExpr expr;

        public Not(RJSRSExpr expr) {
            this.expr = expr;
        }
    }

    // endregion

    // region VALUE_CONSTRUCTORS

    public static final class True extends RJSRSExpr {
        public True() {
        }
    }

    public static final class False extends RJSRSExpr {
        public False() {
        }
    }

    public static final class AspectSet extends RJSRSExpr {
        public static final class AspectSetMember {
            public ID<RJSAspect> aspect;

            public RJSRSExpr condition;

            public AspectSetMember(ID<RJSAspect> aspect, RJSRSExpr condition) {
                this.aspect = aspect;
                this.condition = condition;
            }
        }

        public AspectSetMember[] members;

        public AspectSet(AspectSetMember[] members) {
            this.members = members;
        }
    }

    public static final class SignalRef extends RJSRSExpr {
        public ID<RJSSignal> signal;

        public SignalRef(ID<RJSSignal> signal) {
            this.signal = signal;
        }
    }

    public static final class RouteRef extends RJSRSExpr {
        public ID<RJSRoute> route;

        public RouteRef(ID<RJSRoute> route) {
            this.route = route;
        }
    }

    public static final class SwitchRef extends RJSRSExpr {
        @Json(name = "switch")
        public ID<RJSSwitch> switchRef;

        public SwitchRef(ID<RJSSwitch> switchRef) {
            this.switchRef = switchRef;
        }
    }
    // endregion

    // region CONTROL_FLOW

    public static final class If extends RJSRSExpr {
        @Json(name = "if")
        public RJSRSExpr condition;

        @Json(name = "then")
        public RJSRSExpr branchTrue;

        @Json(name = "else")
        public RJSRSExpr branchFalse;

        /** If the "if" expression returns true, run the "then" branch. Otherwise, run the "else" branch. */
        public If(RJSRSExpr condition, RJSRSExpr branchTrue, RJSRSExpr branchFalse) {
            this.condition = condition;
            this.branchTrue = branchTrue;
            this.branchFalse = branchFalse;
        }
    }

    public static final class Call extends RJSRSExpr {
        public ID<RJSRSFunction> function;

        public RJSRSExpr[] arguments;

        Call(ID<RJSRSFunction> function, RJSRSExpr[] arguments) {
            this.function = function;
            this.arguments = arguments;
        }
    }

    public static final class EnumMatch extends RJSRSExpr {
        public RJSRSExpr expr;

        public Map<String, RJSRSExpr> branches;

        public EnumMatch(
                RJSRSExpr expr,
                Map<String, RJSRSExpr> branches
        ) {
            this.expr = expr;
            this.branches = branches;
        }
    }

    public static final class OptionalMatch extends RJSRSExpr implements Identified {
        public RJSRSExpr expr;

        @Json(name = "case_none")
        public RJSRSExpr caseNone;

        @Json(name = "case_some")
        public RJSRSExpr caseSome;

        public String name;

        /** Matches an optional with an expression, depending on its content */
        public OptionalMatch(
                RJSRSExpr expr,
                RJSRSExpr caseNone,
                RJSRSExpr caseSome,
                String name
        ) {
            this.expr = expr;
            this.caseNone = caseNone;
            this.caseSome = caseSome;
            this.name = name;
        }

        @Override
        public String getID() {
            return name;
        }
    }

    // endregion

    // region REFERENCES

    public static final class ArgumentRef extends RJSRSExpr {
        @Json(name = "argument_name")
        public String argumentName;

        public ArgumentRef(String argumentName) {
            this.argumentName = argumentName;
        }
    }

    public static final class OptionalMatchRef extends RJSRSExpr {
        @Json(name = "match_name")
        public ID<OptionalMatch> optionalMatchName;

        public OptionalMatchRef(ID<OptionalMatch> optionalMatchName) {
            this.optionalMatchName = optionalMatchName;
        }
    }

    // endregion

    // region PRIMITIVES

    public static final class Delay extends RJSRSExpr {
        /** The expression to delay the propagation of */
        public RJSRSExpr expr;

        /** The duration of the delay */
        public double duration;

        public Delay(double duration, RJSRSExpr expr) {
            this.duration = duration;
            this.expr = expr;
        }
    }

    /**
     * Returns whether some signal has a given aspect.
     */
    public static final class SignalAspectCheck extends RJSRSExpr {
        /**
         * The signal the condition checks for.
         */
        public RJSRSExpr signal;

        /** The condition is true when the signal has the following aspect */
        public ID<RJSAspect> aspect;

        public SignalAspectCheck(RJSRSExpr signal, ID<RJSAspect> aspect) {
            this.signal = signal;
            this.aspect = aspect;
        }
    }

    /**
     * Returns whether some route is in a given state.
     */
    public static final class RouteStateCheck extends RJSRSExpr {
        /**
         * The signal the condition checks for.
         */
        public RJSRSExpr route;

        /** The condition is true when the signal has the following aspect */
        public RJSRoute.State state;

        public RouteStateCheck(RJSRSExpr route, RJSRoute.State state) {
            this.route = route;
            this.state = state;
        }
    }

    /**
     * Returns whether some aspect set contains a given aspect.
     */
    public static final class AspectSetContains extends RJSRSExpr {
        /**
         * The signal the condition checks for.
         */
        @Json(name = "aspect_set")
        public RJSRSExpr aspectSet;

        /** The condition is true when the signal has the following aspect */
        public ID<RJSAspect> aspect;

        public AspectSetContains(RJSRSExpr aspectSet, ID<RJSAspect> aspect) {
            this.aspectSet = aspectSet;
            this.aspect = aspect;
        }
    }

    /**
     * Returns the current reserved route containing the signal, if any
     */
    public static final class ReservedRoute extends RJSRSExpr {
        /**
         * The expression giving the signal to look for.
         */
        public RJSRSExpr signal;

        public ReservedRoute(RJSRSExpr signal) {
            this.signal = signal;
        }
    }

    /**
     * Returns the next signal on the given route, if any
     */
    public static final class NextSignal extends RJSRSExpr {
        /**
         * The expression giving the signal used as reference, assumed to be on the route.
         */
        public RJSRSExpr signal;

        /**
         * The expression giving the route to follow.
         */
        public RJSRSExpr route;

        public NextSignal(RJSRSExpr signal, RJSRSExpr route) {
            this.signal = signal;
            this.route = route;
        }
    }

    /**
     * Returns whether a route preceding the given one is CBTC_RESERVED or
     * CBTC_REQUESTED or CBTC OCCUPIED.
     */
    public static final class IsIncomingRouteCBTC extends RJSRSExpr {
        /**
         * The signal the condition checks for.
         */
        public RJSRSExpr route;

        public IsIncomingRouteCBTC(RJSRSExpr route) {
            this.route = route;
        }
    }
    // endregion
}
