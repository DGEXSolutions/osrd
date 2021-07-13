package fr.sncf.osrd.speedcontroller.generators;

import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.speedcontroller.LimitAnnounceSpeedController;
import fr.sncf.osrd.speedcontroller.MapSpeedController;
import fr.sncf.osrd.speedcontroller.SpeedController;
import fr.sncf.osrd.train.Action;
import fr.sncf.osrd.train.Train;
import fr.sncf.osrd.train.TrainPhysicsIntegrator;
import fr.sncf.osrd.train.TrainPositionTracker;
import fr.sncf.osrd.train.TrainSchedule;

import java.util.HashSet;
import java.util.Set;

/** Adds a construction margin to the given speed limits
 * The allowanceValue is in seconds, added over the whole phase */
public class ConstructionAllowanceGenerator extends DichotomyControllerGenerator {

    public final double value;

    public ConstructionAllowanceGenerator(double begin, double end,
                                          double allowanceValue) {
        super(begin, end, 0.1);
        this.value = allowanceValue;
    }

    @Override
    protected double getTargetTime(double baseTime) {
        return baseTime + value;
    }

    @Override
    protected double getFirstLowEstimate() {
        return 0;
    }

    @Override
    protected double getFirstHighEstimate() {
        double max = 0;
        double position = sectionBegin;
        while (position < sectionEnd) {
            double val = SpeedController.getDirective(maxSpeedControllers, position).allowedSpeed;
            if (val > max)
                max = val;
            position += 1;
        }
        return max;
    }

    @Override
    protected double getFirstGuess() {
        return ((this.getFirstHighEstimate()+this.getFirstLowEstimate()) / 2);
    }

    private static TrainPositionTracker convertPosition(TrainSchedule schedule, Simulation sim, double position) {
        var location = Train.getInitialLocation(schedule, sim);
        location.updatePosition(schedule.rollingStock.length, position);
        return location;
    }

    @Override
    protected Set<SpeedController> getSpeedControllers(TrainSchedule schedule, double value, double initialPosition, double endPosition) {
        var res = new HashSet<>(maxSpeedControllers);

        double timestep = 0.01; // TODO: link this timestep to the rest of the simulation
        var initialSpeedControllers = new HashSet<>(maxSpeedControllers);
        var currentSpeedControllers = new HashSet<>(maxSpeedControllers);
        // running time calculation to get the initial speed of the ROI
        var expectedSpeeds = getExpectedSpeeds(sim, schedule, currentSpeedControllers, timestep,
                0, initialPosition, schedule.initialSpeed);
        var initialSpeed = expectedSpeeds.interpolate(initialPosition);
        double scaleFactor = value / initialSpeed;
        var targetSpeed = value;
        // TODO: fix negative initialPosition
        var requiredBrakingDistance = Double.max(0, (initialSpeed * initialSpeed - targetSpeed * targetSpeed) / 2 * schedule.rollingStock.timetableGamma);
        var endBrakingPosition = initialPosition = requiredBrakingDistance;
        var roiSpeeds = getExpectedSpeeds(sim, schedule, currentSpeedControllers, timestep,
                endBrakingPosition, endPosition, initialSpeed);
        currentSpeedControllers.add(new MapSpeedController(roiSpeeds).scaled(scaleFactor));

        LimitAnnounceSpeedController brakingSpeedController = LimitAnnounceSpeedController.create(
                initialSpeed,
                initialSpeed * scaleFactor,
                endBrakingPosition,
                schedule.rollingStock.timetableGamma
        );
        currentSpeedControllers.add(brakingSpeedController);

        var newSpeeds = getExpectedSpeeds(sim, schedule, currentSpeedControllers, timestep,
                initialPosition, endPosition, initialSpeed);

        // backwards acceleration calculation
        double speed = roiSpeeds.interpolate(endPosition);

        var location = convertPosition(schedule, sim, endPosition);

        do {
            var integrator = TrainPhysicsIntegrator.make(timestep, schedule.rollingStock,
                    speed, location.maxTrainGrade());
            var action = Action.accelerate(-schedule.rollingStock.getMaxEffort(speed));
            var update =  integrator.computeUpdate(action, Double.POSITIVE_INFINITY,
                    -1);
            speed = update.speed;

            // We cannot just call updatePosition with a negative delta so we re-create the location object
            // TODO (optimization): support negative delta
            location = convertPosition(schedule, sim, location.getPathPosition() - update.positionDelta);

        } while(speed > newSpeeds.interpolate(location.getPathPosition()));

        res.add(brakingSpeedController);
        var speedsEndingEarlier = getExpectedSpeeds(sim, schedule, initialSpeedControllers, timestep,
                endBrakingPosition, location.getPathPosition(), initialSpeed);
        res.add(new MapSpeedController(speedsEndingEarlier).scaled(scaleFactor));

        return res;
    }
}