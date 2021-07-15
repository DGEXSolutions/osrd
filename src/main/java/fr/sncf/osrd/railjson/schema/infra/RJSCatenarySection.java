package fr.sncf.osrd.railjson.schema.infra;

import com.squareup.moshi.Json;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.railjson.schema.common.Identified;

@SuppressFBWarnings({"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD"})
public class RJSCatenarySection implements Identified {
    public String id;

    /** the type of curve to use */
    public String type;

    /**
     * Create a RailJSON speed section
     * @param id the curve id
     * @param type the type of curve to use
     */
    public RJSCatenarySection(String id, String type) {
        this.id = id;
        this.type = type;
    }

    @Override
    public String getID() {
        return id;
    }
}
