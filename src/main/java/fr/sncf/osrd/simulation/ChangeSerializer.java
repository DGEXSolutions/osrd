package fr.sncf.osrd.simulation;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.squareup.moshi.*;
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.cbtc.CBTCNavigatePhase;
import fr.sncf.osrd.infra.OperationalPoint;
import fr.sncf.osrd.infra.StopActionPoint;
import fr.sncf.osrd.infra.TVDSection;
import fr.sncf.osrd.infra.railscript.value.RSAspectSet;
import fr.sncf.osrd.infra.railscript.value.RSBool;
import fr.sncf.osrd.infra.railscript.value.RSValue;
import fr.sncf.osrd.infra.routegraph.Route;
import fr.sncf.osrd.infra.signaling.ActionPoint;
import fr.sncf.osrd.infra.signaling.Aspect;
import fr.sncf.osrd.infra.signaling.Signal;
import fr.sncf.osrd.infra.trackgraph.BufferStop;
import fr.sncf.osrd.infra.trackgraph.Detector;
import fr.sncf.osrd.infra.trackgraph.Switch;
import fr.sncf.osrd.infra.trackgraph.TrackSection;
import fr.sncf.osrd.infra_state.RouteState;
import fr.sncf.osrd.infra_state.SignalState;
import fr.sncf.osrd.infra_state.SwitchState;
import fr.sncf.osrd.simulation.changelog.ChangeLog;
import fr.sncf.osrd.speedcontroller.SpeedController;
import fr.sncf.osrd.train.TrackSectionRange;
import fr.sncf.osrd.train.Train.TrainStateChange;
import fr.sncf.osrd.train.decisions.InteractiveInput;
import fr.sncf.osrd.train.decisions.KeyboardInput;
import fr.sncf.osrd.train.decisions.TrainDecisionMaker;
import fr.sncf.osrd.train.decisions.TrainDecisionMaker.DefaultTrainDecisionMaker;
import fr.sncf.osrd.train.phases.NavigatePhase;
import fr.sncf.osrd.train.phases.Phase;
import fr.sncf.osrd.train.phases.PhaseState;
import fr.sncf.osrd.train.phases.SignalNavigatePhase;
import fr.sncf.osrd.utils.CryoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Supplier;

public class ChangeSerializer {
    public static final JsonAdapter<Change> changeAdapter = new Moshi.Builder()
            .add(new CurrentPathEdgesAdapter())
            .add(new TopoEdgeAdapter())
            .add(new RouteAdapter())
            .add(new SignalAdapter())
            .add(new SwitchAdapter())
            .add(new TVDSectionAdapter())
            .add(new AspectAdapter())
            .add(new TimelineEventAdapter())
            .add(CollectionJsonAdapter.of(CryoList.class, CryoList::new))
            .add(CollectionJsonAdapter.of(ArrayList.class, ArrayList::new))
            .add(CollectionJsonAdapter.of(ArrayDeque.class, ArrayDeque::new))
            .add(CollectionJsonAdapter.of(RSAspectSet.class, RSAspectSet::new))
            .add(CollectionJsonAdapter.of(
                    TrainStateChange.PathUpdates.class,
                    TrainStateChange.PathUpdates::new))
            .add(CollectionJsonAdapter.of(
                    TrainStateChange.SpeedUpdates.class,
                    TrainStateChange.SpeedUpdates::new))
            .add(new SerializableDoubleAdapter())
            .add(adaptPolymorphicType(Change.class, "changeType"))
            .add(PolymorphicJsonAdapterFactory.of(Phase.class, "phaseType")
                    .withSubtype(NavigatePhase.class, "navigatePhase")
                    .withSubtype(SignalNavigatePhase.class, "signalNavigatePhase")
                    .withSubtype(CBTCNavigatePhase.class, "cbtcNavigatePhase"))
            .add(PolymorphicJsonAdapterFactory.of(NavigatePhase.class, "navigatePhaseType")
                    .withSubtype(SignalNavigatePhase.class, "signalNavigatePhase")
                    .withSubtype(CBTCNavigatePhase.class, "cbtcNavigatePhase")
            )
            .add(adaptPolymorphicType(PhaseState.class, "phaseStateType"))
            .add(PolymorphicJsonAdapterFactory.of(ActionPoint.class, "actionPointType")
                    .withSubtype(BufferStop.class, "bufferStop")
                    .withSubtype(Detector.class, "detector")
                    .withSubtype(Signal.class, "signal")
                    .withSubtype(OperationalPoint.class, "operationalPoint")
                    .withSubtype(StopActionPoint.class, "stopActionPoint")
                    .withSubtype(NavigatePhase.SwitchActionPoint.class,
                            "switch")
            )
            .add(PolymorphicJsonAdapterFactory.of(RSValue.class, "valueType")
                    .withSubtype(RSAspectSet.class, "aspectSet")
                    .withSubtype(RSBool.class, "bool")
                    .withSubtype(SignalState.class, "signal")
                    .withSubtype(RouteState.class, "route")
                    .withSubtype(SwitchState.class, "switch")
            )
            .add(PolymorphicJsonAdapterFactory.of(TrainDecisionMaker.class, "trainDecisionMakerType")
                    .withSubtype(DefaultTrainDecisionMaker.class, "defaultTrainDecisionMakerType")
                    .withSubtype(InteractiveInput.class, "interactiveInput")
            )
            .add(PolymorphicJsonAdapterFactory.of(InteractiveInput.class, "interactiveInputType")
                .withSubtype(KeyboardInput.class, "keyboardInputType")
            )
            .add(adaptPolymorphicType(SpeedController.class, "controllerType"))
            .build()
            .adapter(Change.class);

    /**
     * A collection of types and names.
     * It's just a convenient collection to store what names to associate with types.
     * @param <T> the base type
     */
    private static class SubtypeCollection<T> implements Iterable<SubtypeCollection.Subtype<T>> {
        static class Subtype<T> {
            public final Class<? extends T> type;
            public final String label;

            private Subtype(Class<? extends T> type, String label) {
                this.type = type;
                this.label = label;
            }
        }

        @Override
        @NonNull
        public Iterator<Subtype<T>> iterator() {
            return adapters.iterator();
        }

        private final ArrayList<Subtype<T>> adapters = new ArrayList<>();

        public SubtypeCollection<T> add(Class<? extends T> type, String label) {
            adapters.add(new Subtype<T>(type, label));
            return this;
        }

        /**
         * Create the list of subclasses of a class
         * @param baseClass the descriptor of the class of the base type T
         * @param <T> the base type
         * @return  the list of subclasses of the base type T
         */
        @SuppressFBWarnings({"RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE"})
        static <T> SubtypeCollection<T> fromClass(Class<T> baseClass) {
            // Create a list of all classes of the package
            try (ScanResult scanResult = new ClassGraph()
                    .enableClassInfo()
                    .acceptPackages("fr.sncf.osrd")
                    .scan()) {

                // select the subclasses of baseClass
                ClassInfoList subclassesInfos = scanResult.getSubclasses(baseClass.getCanonicalName());

                // create result list
                var results = new SubtypeCollection<T>();

                // iterate over the subclasses
                for (var subclassInfo: subclassesInfos) {
                    // skip abstract classes
                    if (subclassInfo.isAbstract())
                        continue;

                    // load the java class using the classInfo descriptor
                    var subclass = subclassInfo.loadClass(baseClass);
                    String changeLabel = subclassInfo.getSimpleName();
                    results.add(subclass, changeLabel);
                }
                return results;
            }
        }
    }

    private static <T> PolymorphicJsonAdapterFactory<T> adaptPolymorphicType(Class<T> baseClass, String labelKey) {
        var adapterFactory = PolymorphicJsonAdapterFactory.of(baseClass, labelKey);
        for (var subtype : SubtypeCollection.fromClass(baseClass))
            adapterFactory = adapterFactory.withSubtype(subtype.type, subtype.label);
        return adapterFactory;
    }

    @SuppressFBWarnings("URF_UNREAD_FIELD")
    public static final class IdentifiedObject {
        final String id;

        public IdentifiedObject(String id) {
            this.id = id;
        }
    }

    private static class RouteAdapter {
        @ToJson
        IdentifiedObject toJson(Route route) {
            return new IdentifiedObject(route.id);
        }

        @FromJson
        Route fromJson(IdentifiedObject id) {
            throw new RuntimeException("not implemented");
        }
    }

    private static class SignalAdapter {
        @ToJson
        IdentifiedObject toJson(Signal signal) {
            return new IdentifiedObject(signal.id);
        }

        @FromJson
        Signal fromJson(IdentifiedObject id) {
            throw new RuntimeException("not implemented");
        }
    }

    private static class SwitchAdapter {
        @ToJson
        IdentifiedObject toJson(Switch aSwitch) {
            return new IdentifiedObject(aSwitch.id);
        }

        @FromJson
        Switch fromJson(IdentifiedObject id) {
            throw new RuntimeException("not implemented");
        }
    }

    private static class TVDSectionAdapter {
        @ToJson
        IdentifiedObject toJson(TVDSection tvdSection) {
            return new IdentifiedObject(tvdSection.id);
        }

        @FromJson
        TVDSection fromJson(IdentifiedObject id) {
            throw new RuntimeException("not implemented");
        }
    }

    private static class AspectAdapter {
        @ToJson
        IdentifiedObject toJson(Aspect aspect) {
            return new IdentifiedObject(aspect.id);
        }

        @FromJson
        Aspect fromJson(IdentifiedObject id) {
            throw new RuntimeException("not implemented");
        }
    }

    private static class TimelineEventAdapter {
        @ToJson
        TimelineEventId toJson(TimelineEvent timelineEvent) {
            return timelineEvent.eventId;
        }

        @FromJson
        TimelineEvent fromJson(TimelineEventId id) {
            throw new RuntimeException("not implemented");
        }
    }

    private static class TopoEdgeAdapter {
        @ToJson
        IdentifiedObject toJson(TrackSection edge) {
            return new IdentifiedObject(edge.id);
        }

        @FromJson
        TrackSection fromJson(IdentifiedObject edgeId) {
            throw new RuntimeException("not implemented");
        }
    }

    private static class CurrentPathEdgesAdapter {
        @ToJson
        Collection<TrackSectionRange> edgesFromJson(ArrayDeque<TrackSectionRange> currentPathEdges) {
            return currentPathEdges;
        }

        @FromJson
        ArrayDeque<TrackSectionRange> eventToJson(Collection<TrackSectionRange> currentPathEdgesColl) {
            throw new RuntimeException("not implemented");
        }
    }

    /**
     * Serializing +inf and -inf usually fails, as those have no JSON equivalent.
     * This qualifier serializes doubles to string to workaround this issue.
     */
    @Retention(RUNTIME)
    @JsonQualifier
    public @interface SerializableDouble {
    }

    private static class SerializableDoubleAdapter {
        @ToJson
        String toJson(@SerializableDouble double number) {
            return Double.toString(number);
        }

        @FromJson
        @SerializableDouble double fromJson(String number) {
            return Double.parseDouble(number);
        }
    }

    /**
     * Converts collection types to JSON arrays containing their converted contents.
     * Adapted from moshi's CollectionJsonAdapter.java
     */
    abstract static class CollectionJsonAdapter<C extends Collection<T>, T> extends JsonAdapter<C> {
        public static <C extends Collection<T>, T> Factory of(
                Class<?> collectionType, // ArrayDeque.class
                Supplier<Collection<?>> supplier // ArrayDeque::new
        ) {
            return (type, annotations, moshi) -> {
                // the raw type is the one without a type parameter
                Class<?> rawType = Types.getRawType(type);
                if (!annotations.isEmpty())
                    return null;

                // if the type of the objects to adapt isn't something the factory can produce adapters for,
                // return null to tell the frame
                if (rawType != collectionType)
                    return null;

                Type elementType = Types.collectionElementType(type, rawType);
                JsonAdapter<T> elementAdapter = moshi.adapter(elementType);
                return new CollectionJsonAdapter<>(elementAdapter) {
                    @Override
                    // the collection supplier works for any type,
                    // as the collection is empty when created
                    @SuppressWarnings("unchecked")
                    Collection<T> newCollection() {
                        return (Collection<T>) supplier.get();
                    }
                };
            };
        }

        private final JsonAdapter<T> elementAdapter;

        private CollectionJsonAdapter(JsonAdapter<T> elementAdapter) {
            this.elementAdapter = elementAdapter;
        }

        abstract C newCollection();

        @Override
        public C fromJson(JsonReader reader) throws IOException {
            C result = newCollection();
            reader.beginArray();
            while (reader.hasNext()) {
                result.add(elementAdapter.fromJson(reader));
            }
            reader.endArray();
            return result;
        }

        @Override
        public void toJson(JsonWriter writer, C value) throws IOException {
            writer.beginArray();
            if (value != null)
                for (T element : value)
                    elementAdapter.toJson(writer, element);
            writer.endArray();
        }

        @Override
        public String toString() {
            return elementAdapter + ".collection()";
        }
    }


    /**
     * Writes the changelog as JSON to some output stream.
     * @param changeLog the changelog to serialize
     * @param outputStream the output stream
     * @throws IOException {@inheritDoc}
     */
    public static void serializeChangeLog(ChangeLog changeLog, BufferedSink outputStream) throws IOException {
        outputStream.writeByte('[');
        boolean isFirst = true;
        for (var change : changeLog) {
            if (!isFirst)
                outputStream.writeByte(',');

            changeAdapter.toJson(outputStream, change);
            isFirst = false;
        }
        outputStream.writeByte(']');
    }

    /**
     * Writes the changelog as JSON to some output file.
     * @param changeLog the changelog to serialize
     * @param file the output file
     * @throws IOException {@inheritDoc}
     */
    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
            justification = "that's a spotbugs bug :)"
    )
    public static void serializeChangeLog(ChangeLog changeLog, Path file) throws IOException {
        try (
                Sink fileSink = Okio.sink(file);
                var bufferedSink = Okio.buffer(fileSink)
        ) {
            serializeChangeLog(changeLog, bufferedSink);
        }
    }
}
