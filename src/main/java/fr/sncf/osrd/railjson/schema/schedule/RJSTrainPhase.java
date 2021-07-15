package fr.sncf.osrd.railjson.schema.schedule;

import com.squareup.moshi.Json;
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory;
import fr.sncf.osrd.railjson.schema.common.RJSTrackLocation;

/**
 * <p>A train phase corresponds to an operation during the lifetime of a scheduled train.
 * It could be a stop, a maneuver, or a navigation phase.</p>
 *
 * <p>The location of the train when entering a phase is known from the previous phase,
 * and the phase exit state is up to the phase.</p>
 */
public abstract class RJSTrainPhase {
    public static final PolymorphicJsonAdapterFactory<RJSTrainPhase> adapter = (
            PolymorphicJsonAdapterFactory.of(RJSTrainPhase.class, "type")
                    .withSubtype(RJSTrainPhase.Navigate.class, "navigate")
    );

    /** The location of the head of the train when it exits this phase */
    @Json(name = "end_location")
    public RJSTrackLocation endLocation;

    public static final class Navigate extends RJSTrainPhase {
        /** The distance at which the driver can see objects on the tracks */
        @Json(name = "driver_sight_distance")
        public double driverSightDistance;
    }
}
