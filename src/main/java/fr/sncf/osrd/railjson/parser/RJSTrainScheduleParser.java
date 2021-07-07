package fr.sncf.osrd.railjson.parser;

import fr.sncf.osrd.RollingStock;
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
import fr.sncf.osrd.train.phases.StopPhase;
import fr.sncf.osrd.utils.TrackSectionLocation;
import fr.sncf.osrd.utils.graph.EdgeDirection;

import java.util.ArrayList;
import java.util.List;
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

        var expectedPath = parsePath(infra, rjsTrainSchedule.phases, initialLocation);

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

    private static SpeedControllerGenerator parseSpeedControllerGenerator(RJSAllowance allowance,
                                                                          TrainPath path,
                                                                          Infra infra)
            throws InvalidSchedule {
        if (allowance == null)
            return new MaxSpeedGenerator();

        var begin = path.getStartLocation();
        if (allowance.begin != null)
            begin = parseLocation(infra, allowance.begin);
        var end = path.getEndLocation();
        if (allowance.end != null)
            end = parseLocation(infra, allowance.end);

        if (allowance instanceof RJSAllowance.LinearAllowance) {
            var linearAllowance = (RJSAllowance.LinearAllowance) allowance;
            return new LinearAllowanceGenerator(path, begin, end,
                    linearAllowance.allowanceValue, linearAllowance.allowanceType);
        } else if (allowance instanceof RJSAllowance.ConstructionAllowance) {
            var constructionAllowance = (RJSAllowance.ConstructionAllowance) allowance;
            return new ConstructionAllowanceGenerator(path, begin, end,
                    constructionAllowance.allowanceValue);
        } else if (allowance instanceof RJSAllowance.MarecoAllowance) {
            var marecoAllowance = (RJSAllowance.MarecoAllowance) allowance;
            return new MarecoAllowanceGenerator(path, begin, end,
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

    private static List<SpeedControllerGenerator> parseSpeedControllerGenerators(RJSTrainSchedule phase,
                                                                                 TrainPath path,
                                                                                 Infra infra)
            throws InvalidSchedule {
        List<SpeedControllerGenerator> list = new ArrayList<>();
        if (phase.allowances == null)
            return list;
        for (var allowance : phase.allowances) {
            list.add(parseSpeedControllerGenerator(allowance, path, infra));
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

        if (rjsPhase.getClass() == RJSTrainPhase.Stop.class) {
            var rjsStop = (RJSTrainPhase.Stop) rjsPhase;
            return new StopPhase(rjsStop.duration);
        }
        if (rjsPhase.getClass() == RJSTrainPhase.Navigate.class) {
            var rjsNavigate = (RJSTrainPhase.Navigate) rjsPhase;
            var routes = new ArrayList<Route>();
            for (var rjsRoute : rjsNavigate.routes) {
                var route = infra.routeGraph.routeMap.get(rjsRoute.id);
                if (route == null)
                    throw new UnknownRoute("unknown route in navigate phase", rjsRoute.id);
                routes.add(route);
            }

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
                                       TrackSectionLocation start) throws InvalidSchedule {
        var routes = new ArrayList<Route>();
        for (var phase : phases) {
            if (phase.getClass() == RJSTrainPhase.Navigate.class) {
                var navigate = (RJSTrainPhase.Navigate) phase;
                for (var rjsRoute : navigate.routes) {
                    var route = infra.routeGraph.routeMap.get(rjsRoute.id);
                    if (route == null)
                        throw new UnknownRoute("unknown route in navigate phase", rjsRoute.id);
                    if (routes.isEmpty() || routes.get(routes.size() - 1) != route)
                        routes.add(route);
                }
            }
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

    private static List<TrainStop> parseStops(RJSTrainStop[] stops, Infra infra, TrainPath path) throws InvalidSchedule {
        var res = new ArrayList<TrainStop>();
        if (stops == null)
            return res;
        for (var stop : stops) {
            if ((stop.position == null) == (stop.location == null)) {
                throw new InvalidSchedule("Train stop must specify exactly one of position or location");
            }
            double position;
            if (stop.position != null) {
                position = stop.position;
            } else {
                position = path.convertTrackLocation(parseLocation(infra, stop.location));
            }
            res.add(new TrainStop(position, stop.duration));
        }
        return res;
    }
}
