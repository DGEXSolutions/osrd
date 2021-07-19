package fr.sncf.osrd;

import static fr.sncf.osrd.SignalSimulationTest.TestSignal.Aspect.*;
import static org.junit.jupiter.api.Assertions.*;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import fr.sncf.osrd.simulation.*;
import fr.sncf.osrd.simulation.changelog.ArrayChangeLog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

@SuppressWarnings("MissingJavadocMethod")
public class SignalSimulationTest {
    public static final class TestSignalAspectChange extends Change {
        public final TestSignal signal;
        public final TestSignal.Aspect newAspect;

        public TestSignalAspectChange(Simulation sim, TestSignal signal, TestSignal.Aspect newAspect) {
            super(sim);
            this.signal = signal;
            this.newAspect = newAspect;
        }

        public void apply() {
            signal.aspect = newAspect;
        }

        @Override
        public void replay(Simulation sim) {
            throw new RuntimeException("replay not implemented");
        }


        @Override
        public String toString() {
            return String.format(
                    "SignalAspectChange { signal=%s, newAspect=%s } ",
                    signal.name, newAspect.name()
            );
        }
    }

    public static final class AspectChangeEvent extends TimelineEvent {
        public final TestSignal signal;
        public final TestSignal.Aspect newAspect;

        private AspectChangeEvent(
                TimelineEventId eventId,
                TestSignal signal,
                TestSignal.Aspect newAspect
        ) {
            super(eventId);
            this.signal = signal;
            this.newAspect = newAspect;
        }

        @Override
        protected void onOccurrence(Simulation sim) {
            signal.setAspect(sim, newAspect);
        }

        @Override
        protected void onCancellation(Simulation sim) {
        }

        @Override
        @SuppressFBWarnings("BC_UNCONFIRMED_CAST")
        public boolean deepEquals(TimelineEvent other) {
            if (other.getClass() != AspectChangeEvent.class)
                return false;
            var o = (AspectChangeEvent) other;
            return o.signal == signal
                    && o.newAspect == newAspect;
        }

        public static AspectChangeEvent plan(
                Simulation sim,
                double eventTime,
                TestSignal signal,
                TestSignal.Aspect newAspect
        ) {
            var change = new AspectChangePlanned(sim, eventTime, signal, newAspect);
            var event = change.apply(sim);
            sim.publishChange(change);
            return event;
        }

        public static class AspectChangePlanned extends Simulation.TimelineEventCreated {
            public final TestSignal signal;
            public final TestSignal.Aspect newAspect;

            private AspectChangePlanned(
                    Simulation sim,
                    double eventTime,
                    TestSignal signal,
                    TestSignal.Aspect newAspect
            ) {
                super(sim, eventTime);
                this.signal = signal;
                this.newAspect = newAspect;
            }

            private AspectChangeEvent apply(Simulation sim) {
                var event = new AspectChangeEvent(eventId, signal, newAspect);
                super.scheduleEvent(sim, event);
                return event;
            }

            @Override
            public void replay(Simulation sim) {
                apply(sim);
            }

            @Override
            public String toString() {
                return String.format(
                        "AspectChangePlanned { eventId=%s, signal=%s, newAspect=%s } ",
                        eventId, signal.name, newAspect.name()
                );
            }
        }
    }

    public static class TestSignal {
        public enum Aspect {
            RED,
            YELLOW,
            GREEN,
        }

        private Aspect aspect;
        public final String name;
        public double receptionDelay;
        public ArrayList<TestSignal> subscribers;

        TestSignal(String name, double receptionDelay, Aspect aspect) {
            this.name = name;
            this.receptionDelay = receptionDelay;
            this.aspect = aspect;
            this.subscribers = new ArrayList<>();
        }

        public void setAspect(Simulation sim, Aspect newAspect) {
            if (newAspect == aspect)
                return;

            var change = new TestSignalAspectChange(sim, this, newAspect);
            change.apply();
            sim.publishChange(change);

            for (var sub : subscribers)
                sub.masterAspectChanged(sim, change);
        }

        private void masterAspectChanged(
                Simulation sim,
                TestSignalAspectChange event
        )  {
            var newAspect = aspect;
            if (event.newAspect == RED) {
                newAspect = YELLOW;
            } else {
                newAspect = GREEN;
            }

            AspectChangeEvent.plan(
                    sim,
                    sim.getTime() + receptionDelay,
                    this,
                    newAspect
            );
        }
    }

    @Test
    public void testSignaling() throws SimulationError {
        var changelog = new ArrayChangeLog();
        var sim = Simulation.createWithoutInfra(0.0, changelog);
        final var masterSignal = new TestSignal("master", 1., GREEN);
        final var slaveSignal = new TestSignal("slave", 1., GREEN);
        masterSignal.subscribers.add(slaveSignal);

        assertTrue(sim.isSimulationOver());
        AspectChangeEvent.plan(sim, 2., masterSignal, RED);
        assertFalse(sim.isSimulationOver());

        var masterChangeEvent = (AspectChangeEvent) sim.step();
        assertEquals(masterChangeEvent.eventId.scheduledTime, 2., 0.00001);
        assertSame(masterChangeEvent.signal, masterSignal);
        assertSame(masterChangeEvent.newAspect, RED);

        assertFalse(sim.isSimulationOver());

        var slaveChangeEvent = (AspectChangeEvent) sim.step();
        assertEquals(slaveChangeEvent.eventId.scheduledTime, 3., 0.00001);
        assertSame(slaveChangeEvent.signal, slaveSignal);
        assertSame(slaveChangeEvent.newAspect, YELLOW);

        assertTrue(sim.isSimulationOver());

        assertEquals(changelog.size(), 6);
    }
}
