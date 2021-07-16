package fr.sncf.osrd.railjson.schema.rollingstock;

import com.squareup.moshi.Json;
import fr.sncf.osrd.railjson.schema.common.Identified;

import java.util.Map;

public class RJSRollingStock implements Identified {
    /** An unique train identifier */
    public String id;

    /** the length of the train, in meters. */
    public double length;

    /** The mass of the train, in kilograms. */
    public double mass;

    /**
     * Inertia coefficient.
     * The mass alone isn't sufficient to compute accelerations, as the wheels and internals
     * also need force to get spinning. This coefficient can be used to account for the difference.
     * It's without unit: effective mass = mass * inertia coefficient
     */
    @Json(name = "inertia_coefficient")
    public double inertiaCoefficient;

    /** The rolling resistance force formula */
    @Json(name = "rolling_resistance")
    public RJSRollingResistance rollingResistance;

    /** The list of capabilities (protection systems, signaling equipment) the train is able to deal with */
    public RJSTrainCapability[] capabilities;

    /** The max speed of the train, in meters per seconds. */
    @Json(name = "max_speed")
    public double maxSpeed;

    /**
     * The time the train takes to start up, in seconds.
     * During this time, the train's maximum acceleration is limited.
     */
    @Json(name = "startup_time")
    public double startUpTime;

    /** The acceleration to apply during the startup state. */
    @Json(name = "startup_acceleration")
    public double startUpAcceleration;

    /** The maximum acceleration when the train is in its regular operating mode. */
    @Json(name = "comfort_acceleration")
    public double comfortAcceleration;

    /** The naive braking deceleration coefficient for timetabling. */
    @Json(name = "timetable_gamma")
    public double timetableGamma;

    /**
     * Associates a speed to a force.
     * https://en.wikipedia.org/wiki/Tractive_force#Tractive_effort_curves
     */

    @Json(name = "tractive_effort_curves")
    public Map<String, RJSTractiveEffortPoint[]> tractiveEffortCurves;

    @Json(name = "effort_curve_selectors")
    public RJSEffortCurveSelector[] effortCurveSelectors;



    /** Creates a new rolling stock */
    public RJSRollingStock(
            String id,
            double length,
            double mass,
            double inertiaCoefficient,
            RJSRollingResistance rollingResistance,
            RJSTrainCapability[] capabilities,
            double maxSpeed,
            double startUpTime,
            double startUpAcceleration,
            double comfortAcceleration,
            double timetableGamma,
            Map<String, RJSTractiveEffortPoint[]> tractiveEffortCurves
    ) {
        this.id = id;
        this.length = length;
        this.mass = mass;
        this.inertiaCoefficient = inertiaCoefficient;
        this.rollingResistance = rollingResistance;
        this.capabilities = capabilities;
        this.maxSpeed = maxSpeed;
        this.startUpTime = startUpTime;
        this.startUpAcceleration = startUpAcceleration;
        this.comfortAcceleration = comfortAcceleration;
        this.timetableGamma = timetableGamma;
        this.tractiveEffortCurves = tractiveEffortCurves;

    }

    /** Creates an empty rolling stock */
    public RJSRollingStock() {
        this.id = null;
        this.length = Double.NaN;
        this.mass = Double.NaN;
        this.inertiaCoefficient = Double.NaN;
        this.rollingResistance = null;
        this.capabilities = new RJSTrainCapability[0];
        this.maxSpeed = Double.NaN;
        this.startUpTime = Double.NaN;
        this.startUpAcceleration = Double.NaN;
        this.comfortAcceleration = Double.NaN;
        this.timetableGamma = Double.NaN;
        this.tractiveEffortCurves = null;

    }
    public static final class RJSEffortCurveSelector {
        @Json(name = "effort_curve")
        public String effortCurve;
        public RJSEffortCurveSelectorCondition condition;

        public RJSEffortCurveSelector(String effortCurve, RJSEffortCurveSelectorCondition condition) {
            this.effortCurve = effortCurve;
            this.condition = condition;
        }
    }

    public static final class RJSEffortCurveSelectorCondition {
        @Json(name = "catenary_type")
        public String catenaryType;

        public RJSEffortCurveSelectorCondition(String catenaryType) {
            this.catenaryType = catenaryType;
        }
    }

    public static final class RJSTractiveEffortPoint {
        public double speed;
        @Json(name = "max_effort")
        public double maxEffort;

        public RJSTractiveEffortPoint(double speed, double maxEffort) {
            this.speed = speed;
            this.maxEffort = maxEffort;
        }
    }

    @Override
    public String getID() {
        return id;
    }
}
