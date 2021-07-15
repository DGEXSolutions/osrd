package fr.sncf.osrd.speedcontroller.generators;

import static java.lang.Math.min;

import fr.sncf.osrd.train.TrainSchedule;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.speedcontroller.SpeedController;
import fr.sncf.osrd.train.Train;
import fr.sncf.osrd.train.TrainPhysicsIntegrator;
import fr.sncf.osrd.train.TrainPhysicsIntegrator.PositionUpdate;
import fr.sncf.osrd.utils.SortedDoubleMap;

import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** This class is used to generate a set of SpeedController (similar to a speed at any given point). */
public abstract class SpeedControllerGenerator {

    protected double sectionBegin;
    protected double sectionEnd;

    protected SpeedControllerGenerator(double begin, double end) {
        this.sectionBegin = begin;
        this.sectionEnd = end;
    }

    /** Generates the set of SpeedController */
    public abstract Set<SpeedController> generate(Simulation sim, TrainSchedule schedule,
                                                  Set<SpeedController> maxSpeed);

    /** Generates a map of location -> expected time if we follow the given controllers. */
    public static SortedDoubleMap getExpectedTimes(Simulation sim,
                                            TrainSchedule schedule,
                                            Set<SpeedController> controllers,
                                            double timestep,
                                            double begin,
                                            double end,
                                            double initialSpeed) {
        var updatesMap
                = getUpdatesAtPositions(sim, schedule, controllers, timestep, begin, end, initialSpeed);
        var res = new SortedDoubleMap();
        double time = schedule.departureTime;
        for (var k : updatesMap.keySet()) {
            time += updatesMap.get(k).timeDelta;
            res.put(k, time);
        }
        return res;
    }

    /** Generates a map of location -> expected time if we follow the given controllers. */
    public SortedDoubleMap getExpectedTimes(Simulation sim,
                                            TrainSchedule schedule,
                                            Set<SpeedController> controllers,
                                            double timestep) {
        var initialSpeed = findInitialSpeed(sim, schedule, controllers, timestep);
        return getExpectedTimes(sim, schedule, controllers, timestep,
                sectionBegin, sectionEnd, initialSpeed);
    }

    /** Generates a map of location -> expected speed if we follow the given controllers. */
    public SortedDoubleMap getExpectedSpeeds(Simulation sim,
                                             TrainSchedule schedule,
                                             Set<SpeedController> controllers,
                                             double timestep) {
        var initialSpeed = findInitialSpeed(sim, schedule, controllers, timestep);
        return getExpectedSpeeds(sim, schedule, controllers, timestep,
                sectionBegin, sectionEnd, initialSpeed);
    }

    /** Generates a map of location -> expected speed if we follow the given controllers */
    public static SortedDoubleMap getExpectedSpeeds(Simulation sim,
                                             TrainSchedule schedule,
                                             Set<SpeedController> controllers,
                                             double timestep,
                                             double begin,
                                             double end,
                                             double initialSpeed) {
        var updatesMap
                = getUpdatesAtPositions(sim, schedule, controllers, timestep, begin, end, initialSpeed);
        var res = new SortedDoubleMap();
        for (var k : updatesMap.keySet()) {
            res.put(k, updatesMap.get(k).speed);
        }
        return res;
    }

    /** Generates a map of location -> updates if we follow the given controllers. */
    public static NavigableMap<Double, PositionUpdate> getUpdatesAtPositions(Simulation sim,
                                                                      TrainSchedule schedule,
                                                                      Set<SpeedController> controllers,
                                                                      double timestep,
                                                                      double begin,
                                                                      double end,
                                                                      double initialSpeed) {
        var location = Train.getInitialLocation(schedule, sim);
        location.ignoreInfraState = true;
        location.updatePosition(schedule.rollingStock.length, begin);
        var totalLength = 0.;
        for (var range : schedule.plannedPath.trackSectionPath)
            totalLength += range.length();
        totalLength = min(totalLength, end);

        var res = new TreeMap<Double, PositionUpdate>();

        double speed = initialSpeed;
        do {
            var activeControllers = controllers.stream()
                    .filter(x -> x.isActive(location))
                    .collect(Collectors.toSet());
            var directive = SpeedController.getDirective(activeControllers, location.getPathPosition());

            var integrator = TrainPhysicsIntegrator.make(timestep, schedule.rollingStock,
                    speed, location.maxTrainGrade());
            var action = integrator.actionToTargetSpeed(directive, schedule.rollingStock);
            var distanceLeft = min(totalLength - location.getPathPosition(), end - location.getPathPosition());
            var update =  integrator.computeUpdate(action, distanceLeft);
            speed = update.speed;

            location.updatePosition(schedule.rollingStock.length, update.positionDelta);
            res.put(location.getPathPosition(), update);
        } while (location.getPathPosition() + timestep * speed < totalLength && speed > 0);
        return res;
    }

    /** Finds the position (as a double) corresponding to the beginning of the allowance */
    protected double findInitialSpeed(Simulation sim, TrainSchedule schedule, Set<SpeedController> maxSpeed,
                                      double timeStep) {
        if (sectionBegin <= 0)
            return schedule.initialSpeed;
        var speeds = getExpectedSpeeds(sim, schedule, maxSpeed, timeStep,
                0, sectionBegin, schedule.initialSpeed);
        return speeds.lastEntry().getValue();
    }
}
