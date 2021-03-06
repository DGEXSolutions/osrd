package fr.sncf.osrd.railjson.schema.infra;

import com.squareup.moshi.Json;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.railjson.schema.common.Identified;

@SuppressFBWarnings({"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD"})
public class RJSSpeedSection implements Identified {
    public String id;

    /** If the speed limit is signalized on the track */
    @Json(name = "is_signalized")
    public boolean isSignalized;

    /** The speed limit */
    public double speed;

    /**
     * Create a RailJSON speed section
     * @param id the unique SpeedSection id
     * @param isSignalized whether the SpeedSection is signalized
     * @param speed the speed limit of the section
     */
    public RJSSpeedSection(String id, boolean isSignalized, double speed) {
        this.id = id;
        this.isSignalized = isSignalized;
        this.speed = speed;
    }

    @Override
    public String getID() {
        return id;
    }
}
