package fr.sncf.osrd.railjson.parser;

import fr.sncf.osrd.RollingStock;
import fr.sncf.osrd.railjson.schema.common.ID;
import fr.sncf.osrd.railjson.schema.infra.RJSRoute;
import fr.sncf.osrd.railjson.schema.schedule.RJSTrainStop;
import fr.sncf.osrd.speedcontroller.SpeedInstructions;
import fr.sncf.osrd.train.TrainSchedule;
import fr.sncf.osrd.infra.Infra;
import fr.sncf.osrd.infra.routegraph.Route;
import fr.sncf.osrd.railjson.parser.exceptions.InvalidSchedule;
import fr.sncf.osrd.railjson.parser.exceptions.UnknownRollingStock;
import fr.sncf.osrd.railjson.parser.exceptions.UnknownRoute;
import fr.sncf.osrd.railjson.parser.exceptions.UnknownTrackSection;
import fr.sncf.osrd.railjson.schema.common.RJSTrackLocation;
import fr.sncf.osrd.railjson.schema.schedule.RJSAllowance;
import fr.sncf.osrd.railjson.schema.schedule.RJSTrainPhase;
import fr.sncf.osrd.railjson.schema.schedule.RJSTrainSchedule;
import fr.sncf.osrd.speedcontroller.generators.*;
import fr.sncf.osrd.train.TrainPath;
import fr.sncf.osrd.train.TrainStop;
import fr.sncf.osrd.train.decisions.KeyboardInput;
import fr.sncf.osrd.train.decisions.TrainDecisionMaker;
import fr.sncf.osrd.train.phases.Phase;
import fr.sncf.osrd.train.phases.SignalNavigatePhase;
import fr.sncf.osrd.utils.TrackSectionLocation;
import fr.sncf.osrd.utils.graph.EdgeDirection;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

public class RJSTrainScheduleParser {
    /** Parses a RailJSON train schedule */
    public static TrainSchedule parse(
            Infra infra,
            Function<String, RollingStock> rollingStockGetter,
            RJSTrainSchedule rjsTrainSchedule
    ) throws InvalidSchedule {
        var rollingStockID = rjsTrainSchedule.rollingStock.id;
        var rollingStock = rollingStockGetter.apply(rollingStockID);
        if (rollingStock == null)
            throw new UnknownRollingStock(rollingStockID);

        var initialLocation = parseLocation(infra, rjsTrainSchedule.initialHeadLocation);

        var expectedPath = parsePath(infra, rjsTrainSchedule.phases, rjsTrainSchedule.routes, initialLocation);

        var stops = parseStops(rjsTrainSchedule.stops, infra, expectedPath);

        var initialRouteID = rjsTrainSchedule.initialRoute.id;
        var initialRoute = infra.routeGraph.routeMap.get(initialRouteID);
        if (initialRoute == null)
            throw new UnknownRoute("unknown initial route", initialRouteID);

        var initialSpeed = rjsTrainSchedule.initialSpeed;
        if (Double.isNaN(initialSpeed) || initialSpeed < 0)
            throw new InvalidSchedule("invalid initial speed");

        // parse the sequence of phases, keeping track of the location of the train between phases
        var phases = new ArrayList<Phase>();
        var beginLocation = initialLocation;
        for (var rjsPhase : rjsTrainSchedule.phases) {
            var phase = parsePhase(infra, beginLocation, rjsPhase, expectedPath, stops);
            var endLocation = phase.getEndLocation();
            if (endLocation != null)
                beginLocation = endLocation;
            phases.add(phase);
        }

        // find from what direction the train arrives on the initial location
        EdgeDirection initialDirection = null;
        var tvdSectionPaths = initialRoute.tvdSectionsPaths;
        var tvdSectionPathDirs = initialRoute.tvdSectionsPathDirections;

        trackSectionLoop:
        for (int i = 0; i < tvdSectionPaths.size(); i++) {
            var tvdSectionPath = tvdSectionPaths.get(i);
            var tvdSectionPathDir = tvdSectionPathDirs.get(i);
            for (var trackSectionRange : tvdSectionPath.getTrackSections(tvdSectionPathDir)) {
                if (trackSectionRange.containsLocation(initialLocation)) {
                    initialDirection = trackSectionRange.direction;
                    break trackSectionLoop;
                }
            }
        }

        var targetSpeedGenerators = parseSpeedControllerGenerators(rjsTrainSchedule,
                expectedPath, infra);
        var speedInstructions = new SpeedInstructions(targetSpeedGenerators);

        if (initialDirection == null)
            throw new InvalidSchedule("the initial location isn't on the initial route");

        return new TrainSchedule(
                rjsTrainSchedule.id,
                rollingStock,
                rjsTrainSchedule.departureTime,
                initialLocation,
                initialDirection,
                initialRoute,
                initialSpeed,
                phases,
                parseDecisionMaker(rjsTrainSchedule.trainControlMethod),
                expectedPath,
                speedInstructions,
                stops);
    }

    private static double[] parseAllowanceBeginEnd(RJSAllowance allowance,
                                                   TrainPath path,
                                                   Infra infra) throws InvalidSchedule {
        if (allowance.beginLocation != null && allowance.beginPosition != null)
            throw new InvalidSchedule("Can't set both begin_location and begin_position for an allowance");
        if (allowance.endLocation != null && allowance.endPosition != null)
            throw new InvalidSchedule("Can't set both end_location and end_position for an allowance");

        double begin = 0;
        double end = Double.POSITIVE_INFINITY;

        if (allowance.beginLocation != null)
            begin = path.convertTrackLocation(parseLocation(infra, allowance.beginLocation));
        else if (allowance.beginPosition != null)
            begin = allowance.beginPosition;

        if (allowance.endLocation != null)
            end = path.convertTrackLocation(parseLocation(infra, allowance.endLocation));
        else if (allowance.endPosition != null)
            end = allowance.endPosition;

        return new double[]{begin, end};
    }

    private static SpeedControllerGenerator parseSpeedControllerGenerator(RJSAllowance allowance,
                                                                          TrainPath path,
                                                                          Infra infra)
            throws InvalidSchedule {
        if (allowance == null)
            return new MaxSpeedGenerator();

        var beginAndEnd = parseAllowanceBeginEnd(allowance, path, infra);
        var begin = beginAndEnd[0];
        var end = beginAndEnd[1];

        if (allowance instanceof RJSAllowance.LinearAllowance) {
            var linearAllowance = (RJSAllowance.LinearAllowance) allowance;
            return new LinearAllowanceGenerator(begin, end,
                    linearAllowance.allowanceValue, linearAllowance.allowanceType);
        } else if (allowance instanceof RJSAllowance.ConstructionAllowance) {
            var constructionAllowance = (RJSAllowance.ConstructionAllowance) allowance;
            return new ConstructionAllowanceGenerator(begin, end,
                    constructionAllowance.allowanceValue);
        } else if (allowance instanceof RJSAllowance.MarecoAllowance) {
            var marecoAllowance = (RJSAllowance.MarecoAllowance) allowance;
            return new MarecoAllowanceGenerator(begin, end,
                    marecoAllowance.allowanceValue, marecoAllowance.allowanceType);
        } else {
            throw new InvalidSchedule("Unimplemented allowance type");
        }
    }

    private static TrainDecisionMaker parseDecisionMaker(String decisionMakerType) throws InvalidSchedule {
        if (decisionMakerType == null || decisionMakerType.equals("default")) {
            return new TrainDecisionMaker.DefaultTrainDecisionMaker();
        } else if (decisionMakerType.equals("keyboard")) {
            return new KeyboardInput(2);
        } else {
            throw new InvalidSchedule(String.format("Unknown decision maker type: %s", decisionMakerType));
        }
    }

    private static List<Set<SpeedControllerGenerator>> parseSpeedControllerGenerators(RJSTrainSchedule phase,
                                                                                      TrainPath path,
                                                                                      Infra infra)
            throws InvalidSchedule {
        List<Set<SpeedControllerGenerator>> list = new ArrayList<>();
        if (phase.allowances == null)
            return list;
        for (var allowanceSet : phase.allowances) {
            var set = new HashSet<SpeedControllerGenerator>();
            for (var allowance : allowanceSet)
                set.add(parseSpeedControllerGenerator(allowance, path, infra));
            list.add(set);
        }
        return list;
    }

    private static Phase parsePhase(
            Infra infra,
            TrackSectionLocation startLocation,
            RJSTrainPhase rjsPhase,
            TrainPath expectedPath,
            List<TrainStop> stops
    ) throws InvalidSchedule {

        if (rjsPhase.getClass() == RJSTrainPhase.Navigate.class) {
            var rjsNavigate = (RJSTrainPhase.Navigate) rjsPhase;
            var driverSightDistance = rjsNavigate.driverSightDistance;
            if (Double.isNaN(driverSightDistance) || driverSightDistance < 0)
                throw new InvalidSchedule("invalid driver sight distance");

            var endLocation = parseLocation(infra, rjsNavigate.endLocation);
            return SignalNavigatePhase.from(driverSightDistance, startLocation,
                    endLocation, expectedPath, stops);
        }
        throw new RuntimeException("unknown train phase");
    }

    private static TrainPath parsePath(Infra infra,
                                       RJSTrainPhase[] phases,
                                       ID<RJSRoute>[] rjsRoutes,
                                       TrackSectionLocation start) throws InvalidSchedule {
        var routes = new ArrayList<Route>();
        for (var rjsRoute : rjsRoutes) {
            var route = infra.routeGraph.routeMap.get(rjsRoute.id);
            if (route == null)
                throw new UnknownRoute("unknown route in train path", rjsRoute.id);
            routes.add(route);
        }

        var rjsEndLocation = phases[phases.length - 1].endLocation;
        return new TrainPath(routes, start, parseLocation(infra, rjsEndLocation));
    }

    private static TrackSectionLocation parseLocation(Infra infra, RJSTrackLocation location) throws InvalidSchedule {
        var trackSectionID = location.trackSection.id;
        var trackSection = infra.trackGraph.trackSectionMap.get(trackSectionID);
        if (trackSection == null)
            throw new UnknownTrackSection("unknown section", trackSectionID);
        var offset = location.offset;
        if (offset < 0 || offset > trackSection.length)
            throw new InvalidSchedule("invalid track section offset");
        return new TrackSectionLocation(trackSection, offset);
    }

    private static List<TrainStop> parseStops(RJSTrainStop[] stops, Infra infra, TrainPath path)
            throws InvalidSchedule {
        var res = new ArrayList<TrainStop>();
        res.add(new TrainStop(path.length - 10, 0));
        if (stops == null)
            return res;
        for (var stop : stops) {
            if ((stop.position == null) == (stop.location == null))
                throw new InvalidSchedule("Train stop must specify exactly one of position or location");
            double position;
            if (stop.position != null)
                position = stop.position;
            else
                position = path.convertTrackLocation(parseLocation(infra, stop.location));
            res.add(new TrainStop(position, stop.duration));
        }
        return res;
    }
}
