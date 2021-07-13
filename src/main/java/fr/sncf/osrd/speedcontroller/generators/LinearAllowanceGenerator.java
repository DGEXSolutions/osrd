package fr.sncf.osrd.speedcontroller.generators;

import fr.sncf.osrd.railjson.schema.schedule.RJSAllowance.LinearAllowance.MarginType;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.speedcontroller.MapSpeedController;
import fr.sncf.osrd.speedcontroller.SpeedController;

import fr.sncf.osrd.train.TrainSchedule;
import fr.sncf.osrd.utils.SortedDoubleMap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class LinearAllowanceGenerator extends SpeedControllerGenerator {

    public final MarginType allowanceType;
    public final double value;

    /** Constructor */
    public LinearAllowanceGenerator(double begin, double end,
                                    double allowanceValue, MarginType allowanceType) {
        super(begin, end);
        this.allowanceType = allowanceType;
        this.value = allowanceValue;
    }

    @Override
    public Set<SpeedController> generate(Simulation sim, TrainSchedule schedule,
                                         Set<SpeedController> maxSpeed) {
        double timeStep = 1;
        // find the percentage of the allowance to add to the whole path
        double percentage;
        if (allowanceType.equals(MarginType.TIME))
            percentage = value;
        else {
            var expectedTime = getExpectedTimes(sim, schedule, maxSpeed, 1);
            var totalTime = expectedTime.lastEntry().getValue() - expectedTime.firstEntry().getValue();
            var schemaLength = expectedTime.lastEntry().getKey() - expectedTime.firstEntry().getKey();
            var n = schemaLength / 100000;
            var totalAllowance = n * value;
            percentage = 100.0 * totalAllowance / totalTime;
        }
        double scaleFactor = 1 / (1 + percentage / 100);

        // This is needed to have a similar distance delta per step, needed for the shift (see next comment)
        double expectedSpeedTimeStep = timeStep * scaleFactor;

        var expectedSpeeds = getExpectedSpeeds(sim, schedule, maxSpeed, expectedSpeedTimeStep);

        // We shift the speed limits by one position: the max speed at t is the speed at t + dt
        // This is because we use the position of the train to evaluate the target speed at the next simulation step
        // Once this offset is removed (and the associated bugs fixed), we can remove this block
        var keys = new ArrayList<>(expectedSpeeds.navigableKeySet());
        var speedLimits = new SortedDoubleMap();
        for (int i = 1; i < keys.size(); i++)
            speedLimits.put(keys.get(i - 1), expectedSpeeds.get(keys.get(i)));
        speedLimits.put(expectedSpeeds.lastKey(), expectedSpeeds.lastEntry().getValue());

        var speedController = new MapSpeedController(speedLimits, sectionBegin, sectionEnd);
        return addSpeedController(maxSpeed, speedController.scaled(scaleFactor));
    }

    static Set<SpeedController> addSpeedController(Set<SpeedController> speedControllers,
                                                   SpeedController newSpeedController) {
        var res = new HashSet<>(speedControllers);
        res.add(newSpeedController);
        return res;
    }
}