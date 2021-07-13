package fr.sncf.osrd.speedcontroller.generators;

import fr.sncf.osrd.railjson.schema.schedule.RJSAllowance;
import fr.sncf.osrd.railjson.schema.schedule.RJSAllowance.MarecoAllowance.MarginType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.train.TrainSchedule;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.speedcontroller.CoastingSpeedController;
import fr.sncf.osrd.speedcontroller.LimitAnnounceSpeedController;
import fr.sncf.osrd.speedcontroller.MaxSpeedController;
import fr.sncf.osrd.speedcontroller.SpeedController;
import fr.sncf.osrd.train.Action;
import fr.sncf.osrd.train.Train;
import fr.sncf.osrd.train.TrainPhysicsIntegrator;
import fr.sncf.osrd.train.TrainPositionTracker;
import fr.sncf.osrd.utils.SortedDoubleMap;

import java.util.*;

public class MarecoAllowanceGenerator extends DichotomyControllerGenerator {

    // TODO use this parameter
    @SuppressFBWarnings({"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD"})
    public final RJSAllowance.MarecoAllowance.MarginType allowanceType;
    public final double value;

    /** Constructor */
    public MarecoAllowanceGenerator(double begin, double end,
                                    double allowanceValue, MarginType allowanceType) {
        super(begin, end, 5);
        this.allowanceType = allowanceType;
        this.value = allowanceValue;
    }

    @Override
    protected double getTargetTime(double baseTime) {
        return baseTime * (1 + value / 100);
    }

    public double vf(double v1) {
        var a = schedule.rollingStock.A;
        var b = schedule.rollingStock.B;
        var c = schedule.rollingStock.C;
        return (2*c*v1*v1*v1 + b*v1*v1)/(3*c*v1*v1 + 2*b*v1 + a);
    }

    // we will try to find v1 so that f(v1, vmax) == 0
    public double f(double v1, double vmax) {
        return vf(v1) - vmax;
    }

    //df(v1, vmax)/dv1 /*first derivative*/
    public double fprime(double v1, double vmax) {
        var a = schedule.rollingStock.A;
        var b = schedule.rollingStock.B;
        var c = schedule.rollingStock.C;
        var v = (3*c*v1*v1 + 2*b*v1 + a);
        var vprime = (6*c*v1 + 2*b);
        var u = (2*c*v1*v1*v1 + b*v1*v1) - vmax * v;
        var uprime = (6*c*v1*v1 + 2*b*v1) - vmax * vprime;
        return (uprime * v - vprime * u) / (v * v);
    }

    @Override
    protected double getFirstLowEstimate() {
        return 0;
    }

    // get the high boundary for the binary search, corresponding to vf = max
    @Override
    protected double getFirstHighEstimate() {
        double max = 0;
        double position = sectionBegin;
        double endLocation = sectionEnd;
        // get max allowed speed
        while (position < endLocation) {
            double val = SpeedController.getDirective(maxSpeedControllers, position).allowedSpeed;
            if (val > max)
                max = val;
            position += 1;
        }

        double tolerance = .000001; // Stop if you're close enough
        int max_count = 200; // Maximum number of Newton's method iterations
        double x = max * 3/2; // at high v1 the equation vf = f(v1) tends to be vf = 2*v1/3

        for( int count=1;
            //Carry on till we're close, or we've run it 200 times.
             (Math.abs(f(x, max)) > tolerance) && ( count < max_count);
             count ++)  {

            x= x - f(x, max)/fprime(x, max);  //Newtons method.
        }

        if( Math.abs(f(x, max)) <= tolerance) {
            return x;
        } else {
            return max * 2; // if no value has been found return a high value to have some margin
        }
    }

    @Override
    protected double getFirstGuess() {
        return this.getFirstHighEstimate()/(1 + value / 100);
    }

    private List<Double> findPositionSameSpeedAsVF(NavigableMap<Double, Double> speeds, double vf) {
        // TODO check only in deceleration intervals
        boolean isLastSpeedBelowVF = true;
        var res = new ArrayList<Double>();
        for (var position : speeds.navigableKeySet()) {
            var speed = speeds.get(position);
            boolean isCurrentSpeedBelowVF = speed < vf;
            if (isCurrentSpeedBelowVF && !isLastSpeedBelowVF && isDecelerating(position)) {
                res.add(position);
            }
            isLastSpeedBelowVF = isCurrentSpeedBelowVF;
        }
        return res;
    }

    private List<Double> findDecelerationPhases(double vf) {
        var res = new ArrayList<Double>();
        for (var announcer : findLimitSpeedAnnouncers(maxSpeedControllers)) {
            if (announcer.targetSpeedLimit > vf)
                res.add(announcer.endPosition);
        }
        return res;
    }

    private static TrainPositionTracker convertPosition(TrainSchedule schedule, Simulation sim, double position) {
        var location = Train.getInitialLocation(schedule, sim);
        location.updatePosition(schedule.rollingStock.length, position);
        return location;
    }

    private CoastingSpeedController generateCoastingSpeedControllerAtPosition(SortedDoubleMap speeds,
                                                                              double endLocation, double timestep) {
        double speed = speeds.interpolate(endLocation);

        var location = convertPosition(schedule, sim, endLocation);

        do {
            var integrator = TrainPhysicsIntegrator.make(timestep, schedule.rollingStock,
                    speed, location.maxTrainGrade());
            var action = Action.coast();
            var update =  integrator.computeUpdate(action, Double.POSITIVE_INFINITY,
                    -1);
            speed = update.speed;

            // We cannot just call updatePosition with a negative delta so we re-create the location object
            // TODO (optimization): support negative delta
            location = convertPosition(schedule, sim, location.getPathPosition() - update.positionDelta);

        } while (speed < speeds.interpolate(location.getPathPosition()));
        return new CoastingSpeedController(location.getPathPosition(), endLocation);
    }

    private boolean isDecelerating(double position) {
        // TODO optimize this
        var announcers = findLimitSpeedAnnouncers(maxSpeedControllers);
        for (var announcer : announcers) {
            if (announcer.isActive(position))
                return true;
        }
        return false;
    }

    private Set<LimitAnnounceSpeedController> findLimitSpeedAnnouncers(Set<SpeedController> controllers) {
        var res = new HashSet<LimitAnnounceSpeedController>();
        for (var c : controllers) {
            if (c instanceof LimitAnnounceSpeedController)
                res.add((LimitAnnounceSpeedController) c);
        }
        return res;
    }

    @Override
    protected Set<SpeedController> getSpeedControllers(TrainSchedule schedule, double v1, double startLocation, double endLocation) {
        double timestep = 0.01; // TODO: link this timestep to the rest of the simulation
        var vf = vf(v1);

        var currentSpeedControllers = new HashSet<>(maxSpeedControllers);
        currentSpeedControllers.add(new MaxSpeedController(v1, startLocation, endLocation));
        var expectedSpeeds = getExpectedSpeeds(sim, schedule, currentSpeedControllers, timestep);

        for (var location : findPositionSameSpeedAsVF(expectedSpeeds, vf)) {
            var controller = generateCoastingSpeedControllerAtPosition(expectedSpeeds, location, timestep);
            currentSpeedControllers.add(controller);
        }
        for (var location : findDecelerationPhases(vf)) {
            var controller = generateCoastingSpeedControllerAtPosition(expectedSpeeds, location, timestep);
            currentSpeedControllers.add(controller);
        }
        return currentSpeedControllers;
    }
}
