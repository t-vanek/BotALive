package dev.botalive.core.ai;

import dev.botalive.api.ai.Goal;
import dev.botalive.api.bot.Bot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Kontrakt životního cyklu cíle: nové {@code pause()}/{@code resume()} se
 * defaultně chovají jako {@code stop()}/{@code start()} (zpětná kompatibilita –
 * plugin cíle nemusí nic měnit), a cíl je smí přepsat na navázání bez úklidu.
 */
class GoalLifecycleTest {

    /** Cíl, který jen zaznamenává, které lifecycle metody se zavolaly. */
    private static class RecordingGoal implements Goal {
        final List<String> calls = new ArrayList<>();

        @Override
        public String id() {
            return "rec";
        }

        @Override
        public double utility(Bot bot) {
            return 0;
        }

        @Override
        public void start(Bot bot) {
            calls.add("start");
        }

        @Override
        public void tick(Bot bot) {
            calls.add("tick");
        }

        @Override
        public void stop(Bot bot) {
            calls.add("stop");
        }

        @Override
        public boolean finished(Bot bot) {
            return false;
        }
    }

    @Test
    void pauseResumeDefaultneDelajiStopStart() {
        RecordingGoal g = new RecordingGoal();
        g.pause(null);
        g.resume(null);
        assertEquals(List.of("stop", "start"), g.calls,
                "výchozí pause=stop, resume=start – žádná změna chování");
    }

    @Test
    void cilSmiPrepsatNaNavazaniBezUklidu() {
        // Cíl s vlastní kontinuitou (jako MineGoal) neúklidí ani nerestartuje –
        // stav se zachová, aby po přerušení pokračoval tam, kde skončil.
        RecordingGoal g = new RecordingGoal() {
            @Override
            public void pause(Bot bot) {
                calls.add("pause");
            }

            @Override
            public void resume(Bot bot) {
                calls.add("resume");
            }
        };
        g.pause(null);
        g.resume(null);
        assertEquals(List.of("pause", "resume"), g.calls,
                "přepsané pause/resume nevolají stop/start – rozdělaný stav přežije");
    }
}
