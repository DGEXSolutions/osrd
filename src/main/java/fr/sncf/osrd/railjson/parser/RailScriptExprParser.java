package fr.sncf.osrd.railjson.parser;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.infra.InvalidInfraException;
import fr.sncf.osrd.infra.railscript.RSExpr;
import fr.sncf.osrd.infra.railscript.RSFunction;
import fr.sncf.osrd.infra.railscript.RSStatefulExpr;
import fr.sncf.osrd.infra.railscript.value.RSAspectSet;
import fr.sncf.osrd.infra.railscript.value.RSBool;
import fr.sncf.osrd.infra.railscript.value.RSOptional;
import fr.sncf.osrd.infra.railscript.value.RSType;
import fr.sncf.osrd.infra.signaling.Aspect;
import fr.sncf.osrd.infra_state.RouteState;
import fr.sncf.osrd.infra_state.RouteStatus;
import fr.sncf.osrd.infra_state.SignalState;
import fr.sncf.osrd.railjson.schema.infra.RJSRoute;
import fr.sncf.osrd.railjson.schema.infra.railscript.RJSRSExpr;
import fr.sncf.osrd.railjson.schema.infra.railscript.RJSRSFunction;
import fr.sncf.osrd.railjson.schema.infra.railscript.RJSRSType;

import java.util.HashMap;

public class RailScriptExprParser {
    private final HashMap<String, Aspect> aspectsMap;
    private final HashMap<String, RSFunction<?>> scriptFunctions;
    private final String[] argNames;
    private final RSType[] argTypes;
    private final HashMap<String, RSType> varTypes;

    /** The number of slots required to evaluate the function */
    public int argSlotCount = 0;
    public int delaySlotCount = 0;

    /** Reserve slots for arguments, and return the start of the allocated index space */
    public int reserveArgSlots(int argSlotCount) {
        var res = this.argSlotCount;
        this.argSlotCount += argSlotCount;
        return res;
    }

    /** Reserve slots for delayed values, and return the start of the allocated index space */
    public int reserveDelaySlots(int delaySlotCount) {
        var res = this.delaySlotCount;
        this.delaySlotCount += delaySlotCount;
        return res;
    }

    public void reserveSlots(int argSlotCount, int delaySlotCount) {
        this.argSlotCount += argSlotCount;
        this.delaySlotCount += delaySlotCount;
    }

    private RailScriptExprParser(
            HashMap<String, Aspect> aspectsMap,
            HashMap<String, RSFunction<?>> scriptFunctions,
            String[] argNames,
            RSType[] argTypes
    ) {
        this.aspectsMap = aspectsMap;
        this.scriptFunctions = scriptFunctions;
        this.argNames = argNames;
        this.argTypes = argTypes;
        varTypes = new HashMap<>();
    }

    public RailScriptExprParser(HashMap<String, Aspect> aspectsMap, HashMap<String, RSFunction<?>> scriptFunctions) {
        this(aspectsMap, scriptFunctions, null, null);
    }

    /** Parse a serialize Json function */
    public static RSFunction<?> parseFunction(
            HashMap<String, Aspect> aspectsMap,
            HashMap<String, RSFunction<?>> scriptFunctions,
            RJSRSFunction rjsSignalFunction
    ) throws InvalidInfraException {
        var arguments = rjsSignalFunction.arguments;

        // type check rules
        var argumentTypes = new RSType[arguments.length];
        var argumentNames = new String[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            argumentNames[i] = arguments[i].name;
            argumentTypes[i] = parseExprType(arguments[i].type);
        }

        var parser = new RailScriptExprParser(aspectsMap, scriptFunctions, argumentNames, argumentTypes);

        // reserve slots for arguments before parsing
        parser.reserveArgSlots(arguments.length);

        var expr = parser.parse(rjsSignalFunction.body);
        return RSFunction.from(
                rjsSignalFunction.name,
                argumentNames,
                argumentTypes,
                parseExprType(rjsSignalFunction.returnType),
                expr,
                parser.argSlotCount,
                parser.delaySlotCount
        );
    }

    /** Parses an AspectSet expression */
    public static RSStatefulExpr<RSAspectSet> parseStatefulSignalExpr(
            HashMap<String, Aspect> aspectsMap,
            HashMap<String, RSFunction<?>> scriptFunctions,
            RJSRSExpr rjsExpr
    ) throws InvalidInfraException {
        var parser = new RailScriptExprParser(aspectsMap, scriptFunctions);
        var expr = parser.parseAspectSetExpr(rjsExpr);
        return new RSStatefulExpr<>(expr, parser.argSlotCount, parser.delaySlotCount);
    }

    private static RSType parseExprType(RJSRSType type) {
        switch (type) {
            case BOOLEAN:
                return RSType.BOOLEAN;
            case SIGNAL:
                return RSType.SIGNAL;
            case ROUTE:
                return RSType.ROUTE;
            case SWITCH:
                return RSType.SWITCH;
            case ASPECT_SET:
                return RSType.ASPECT_SET;
        }
        throw new RuntimeException("unknown RJSSignalExprType");
    }

    private int findArgIndex(String argument) throws InvalidInfraException {
        for (int i = 0; i < argNames.length; i++)
            if (argNames[i].equals(argument))
                return i;

        throw new InvalidInfraException(String.format("signal function argument not found: %s", argument));
    }

    /** Turns a Json serialized expression into its runnable counterpart */
    @SuppressFBWarnings({"BC_UNCONFIRMED_CAST"})
    public RSExpr<?> parse(RJSRSExpr expr) throws InvalidInfraException {
        var type = expr.getClass();

        // boolean operators
        if (type == RJSRSExpr.Or.class)
            return new RSExpr.Or(parseInfixOp((RJSRSExpr.InfixOp) expr));
        if (type == RJSRSExpr.And.class)
            return new RSExpr.And(parseInfixOp((RJSRSExpr.InfixOp) expr));
        if (type == RJSRSExpr.Not.class) {
            var notExpr = (RJSRSExpr.Not) expr;
            return new RSExpr.Not(parseBooleanExpr(notExpr.expr));
        }

        // value constructors
        if (type == RJSRSExpr.True.class)
            return RSExpr.True.INSTANCE;
        if (type == RJSRSExpr.False.class)
            return RSExpr.False.INSTANCE;
        if (type == RJSRSExpr.AspectSet.class)
            return parseAspectSet((RJSRSExpr.AspectSet) expr);
        if (type == RJSRSExpr.SignalRef.class)
            return new RSExpr.SignalRef(((RJSRSExpr.SignalRef) expr).signal.id);
        if (type == RJSRSExpr.RouteRef.class)
            return new RSExpr.RouteRef(((RJSRSExpr.RouteRef) expr).route.id);
        if (type == RJSRSExpr.SwitchRef.class)
            return new RSExpr.SwitchRef(((RJSRSExpr.SwitchRef) expr).switchRef.id);

        // control flow
        if (type == RJSRSExpr.If.class)
            return parseIfExpr((RJSRSExpr.If) expr);
        if (type == RJSRSExpr.Call.class)
            return parseCallExpr((RJSRSExpr.Call) expr);
        if (type == RJSRSExpr.EnumMatch.class)
            return parseEnumMatch((RJSRSExpr.EnumMatch) expr);
        if (type == RJSRSExpr.OptionalMatch.class) {
            return parseOptionalMatch(expr);
        }

        // references
        if (type == RJSRSExpr.ArgumentRef.class) {
            var argumentExpr = (RJSRSExpr.ArgumentRef) expr;
            // if the function has arguments, its argument slots are the first in line
            var argIndex = findArgIndex(argumentExpr.argumentName);
            return new RSExpr.ArgumentRef<>(argIndex);
        }
        if (type == RJSRSExpr.OptionalMatchRef.class) {
            var refExpr = (RJSRSExpr.OptionalMatchRef) expr;
            return new RSExpr.OptionalMatchRef<>(refExpr.optionalMatchName.id, varTypes);
        }

        // primitives
        if (type == RJSRSExpr.Delay.class) {
            var delayExpr = (RJSRSExpr.Delay) expr;
            var delaySlotIndex = reserveDelaySlots(1);
            return new RSExpr.Delay<>(delayExpr.duration, parse(delayExpr.expr), delaySlotIndex);
        }
        if (type == RJSRSExpr.SignalAspectCheck.class) {
            var signalExpr = (RJSRSExpr.SignalAspectCheck) expr;
            var aspect = aspectsMap.get(signalExpr.aspect.id);
            var signal = parseSignalExpr(signalExpr.signal);
            return new RSExpr.SignalAspectCheck(signal, aspect);
        }
        if (type == RJSRSExpr.RouteStateCheck.class) {
            var routeStateExpr = (RJSRSExpr.RouteStateCheck) expr;
            var route = parseRouteExpr(routeStateExpr.route);
            var routeState = parseRouteState(routeStateExpr.state);
            return new RSExpr.RouteStateCheck(route, routeState);
        }
        if (type == RJSRSExpr.AspectSetContains.class) {
            var aspectSetContainsExpr = (RJSRSExpr.AspectSetContains) expr;
            var aspectSet = parseAspectSetExpr(aspectSetContainsExpr.aspectSet);
            var aspect = aspectsMap.get(aspectSetContainsExpr.aspect.id);
            return new RSExpr.AspectSetContains(aspectSet, aspect);
        }
        if (type == RJSRSExpr.ReservedRoute.class) {
            var reservedRouteExpr = (RJSRSExpr.ReservedRoute) expr;
            var signalExpr = parseSignalExpr(reservedRouteExpr.signal);
            return new RSExpr.ReservedRoute(signalExpr);
        }
        if (type == RJSRSExpr.NextSignal.class) {
            var nextSignalExpr = (RJSRSExpr.NextSignal) expr;
            var signalExpr = parseSignalExpr(nextSignalExpr.signal);
            var routeExpr = parseRouteExpr(nextSignalExpr.route);
            return new RSExpr.NextSignal(signalExpr, routeExpr);
        }
        if (type == RJSRSExpr.IsIncomingRouteCBTC.class) {
            var incomingRouteExpr = (RJSRSExpr.IsIncomingRouteCBTC) expr;
            var route = parseRouteExpr(incomingRouteExpr.route);
            return new RSExpr.IsIncomingRouteCBTC(route);
        }

        throw new InvalidInfraException(String.format("'%s' unsupported signal expression", type));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RSExpr<?> parseEnumMatch(RJSRSExpr.EnumMatch rjsMatchExpr) throws InvalidInfraException {
        var expr = parse(rjsMatchExpr.expr);

        var exprType = expr.getType(argTypes);
        var enumConstants = exprType.enumClass.getEnumConstants();
        var branches = new RSExpr<?>[enumConstants.length];
        for (var matchBranch : rjsMatchExpr.branches.entrySet()) {
            var branchOrdinal = parseEnumVal(enumConstants, matchBranch.getKey());
            branches[branchOrdinal] = parse(matchBranch.getValue());
        }

        for (int i = 0; i < enumConstants.length; i++)
            if (branches[i] == null)
                throw new InvalidInfraException("missing branch for enum value " + enumConstants[i].name());

        var matchType = branches[0].getType(argTypes);
        for (int i = 1; i < enumConstants.length; i++)
            if (branches[i].getType(argTypes) != matchType)
                throw new InvalidInfraException(
                        "type for match branch " + enumConstants[i].name()
                        + " differs from branch type " + enumConstants[0].name());

        return new RSExpr.EnumMatch(expr, branches);
    }

    private static int parseEnumVal(Enum<?>[] enumValues, String val) throws InvalidInfraException {
        for (int i = 0; i < enumValues.length; i++) {
            var enumVal = enumValues[i];
            if (enumVal.name().equals(val))
                return enumVal.ordinal();
        }
        throw new InvalidInfraException("unknown enum value: " + val);
    }

    private RSExpr.OptionalMatch<?> parseOptionalMatch(RJSRSExpr expr) throws InvalidInfraException {
        var optionalMatchExpr = (RJSRSExpr.OptionalMatch) expr;
        var name = optionalMatchExpr.name;
        if (varTypes.containsKey(name))
            throw new InvalidInfraException("Duplicate optional match name " + name);
        var noneExpr = parse(optionalMatchExpr.caseNone);
        var contentExpr = parseOptionalExpr(optionalMatchExpr.expr);
        var type = contentExpr.getType(argTypes);
        var contentType = type.contentType();
        varTypes.put(name, contentType);
        var someExpr = parse(optionalMatchExpr.caseSome);
        varTypes.remove(name);

        if (noneExpr.getType(argTypes) != someExpr.getType(argTypes))
            throw new InvalidInfraException("Mismatched types in optional branches");

        @SuppressWarnings({"unchecked", "rawtypes"})
        var res = new RSExpr.OptionalMatch(contentExpr, noneExpr, someExpr, name);
        return res;
    }

    private static RouteStatus parseRouteState(RJSRoute.State state) {
        switch (state) {
            case FREE:
                return RouteStatus.FREE;
            case REQUESTED:
                return RouteStatus.REQUESTED;
            case RESERVED:
                return RouteStatus.RESERVED;
            case OCCUPIED:
                return RouteStatus.OCCUPIED;
            case CBTC_REQUESTED:
                return RouteStatus.CBTC_REQUESTED;
            case CBTC_RESERVED:
                return RouteStatus.CBTC_RESERVED;
            case CBTC_OCCUPIED:
                return RouteStatus.CBTC_OCCUPIED;
            case CONFLICT:
                return RouteStatus.CONFLICT;
        }
        throw new RuntimeException("unsupported RailJSON route state");
    }

    private RSExpr<?> parseIfExpr(RJSRSExpr.If ifExpr) throws InvalidInfraException {
        var condition = parseBooleanExpr(ifExpr.condition);
        var branchTrue = parse(ifExpr.branchTrue);
        var branchFalse = parse(ifExpr.branchFalse);
        var branchTrueType = branchTrue.getType(argTypes);
        var branchFalseType = branchFalse.getType(argTypes);
        if (branchTrueType != branchFalseType)
            throw new InvalidInfraException(String.format(
                    "then branch has type %s but else has type %s", branchTrueType, branchFalseType));

        // typing is dynamically checked
        @SuppressWarnings({"unchecked", "rawtypes"})
        var res = new RSExpr.If(condition, branchTrue, branchFalse);
        return res;
    }

    private RSExpr<?> parseCallExpr(RJSRSExpr.Call expr) throws InvalidInfraException {
        // parse the arguments
        var rjsArgs = expr.arguments;
        var args = new RSExpr<?>[rjsArgs.length];
        for (int i = 0; i < rjsArgs.length; i++)
            args[i] = parse(rjsArgs[i]);

        // get the function
        var function = scriptFunctions.get(expr.function.id);
        // TODO check that it exists

        // reserve enough slots for the function, and store the slot offset
        var res = new RSExpr.Call<>(function, args, this.argSlotCount, this.delaySlotCount);
        reserveSlots(function.argSlotsCount, function.delaySlotsCount);
        return res;
    }

    private RSExpr<RSAspectSet> parseAspectSet(RJSRSExpr.AspectSet expr) throws InvalidInfraException {
        var memberCount = expr.members.length;
        var aspects = new Aspect[memberCount];
        @SuppressWarnings({"unchecked"})
        var conditions = (RSExpr<RSBool>[]) new RSExpr<?>[memberCount];

        for (int i = 0; i < memberCount; i++) {
            var member = expr.members[i];

            var aspect = aspectsMap.get(member.aspect.id);
            if (aspect == null)
                throw new InvalidInfraException(String.format("unknown aspect %s", member.aspect.id));

            aspects[i] = aspect;
            if (member.condition != null)
                conditions[i] = parseBooleanExpr(member.condition);
        }

        return new RSExpr.AspectSet(aspects, conditions);
    }

    private void checkExprType(RSType expectedType, RSExpr<?> expr) throws InvalidInfraException {
        var exprType = expr.getType(argTypes);
        if (exprType != expectedType)
            throw new InvalidInfraException(String.format(
                    "type mismatch: expected %s, got %s", expectedType.toString(), exprType.toString()));
    }

    @SuppressWarnings("unchecked")
    private RSExpr<RSOptional<?>> parseOptionalExpr(RJSRSExpr rjsExpr) throws InvalidInfraException {
        var expr = parse(rjsExpr);
        var exprType = expr.getType(argTypes);
        if (exprType != RSType.OPTIONAL_ROUTE && exprType != RSType.OPTIONAL_SIGNAL)
            throw new InvalidInfraException(String.format(
                    "type mismatch: expected an optional, got %s", exprType.toString()));
        return (RSExpr<RSOptional<?>>) expr;
    }

    @SuppressWarnings("unchecked")
    private RSExpr<RSBool> parseBooleanExpr(RJSRSExpr rjsExpr) throws InvalidInfraException {
        var expr = parse(rjsExpr);
        checkExprType(RSType.BOOLEAN, expr);
        return (RSExpr<RSBool>) expr;
    }

    @SuppressWarnings("unchecked")
    private RSExpr<SignalState> parseSignalExpr(RJSRSExpr rjsExpr) throws InvalidInfraException {
        var expr = parse(rjsExpr);
        checkExprType(RSType.SIGNAL, expr);
        return (RSExpr<SignalState>) expr;
    }

    @SuppressWarnings("unchecked")
    private RSExpr<RouteState> parseRouteExpr(RJSRSExpr rjsExpr) throws InvalidInfraException {
        var expr = parse(rjsExpr);
        checkExprType(RSType.ROUTE, expr);
        return (RSExpr<RouteState>) expr;
    }

    /** Parse a Json RailScript expression, and ensure it returns an AspectSet */
    @SuppressWarnings("unchecked")
    private RSExpr<RSAspectSet> parseAspectSetExpr(RJSRSExpr rjsExpr) throws InvalidInfraException {
        var expr = parse(rjsExpr);
        checkExprType(RSType.ASPECT_SET, expr);
        return (RSExpr<RSAspectSet>) expr;
    }

    private RSExpr<RSBool>[] parseInfixOp(RJSRSExpr.InfixOp expr) throws InvalidInfraException {
        var arity = expr.exprs.length;
        @SuppressWarnings({"unchecked"})
        var expressions = (RSExpr<RSBool>[]) new RSExpr<?>[arity];
        for (int i = 0; i < arity; i++)
            expressions[i] = parseBooleanExpr(expr.exprs[i]);
        return expressions;
    }

}
