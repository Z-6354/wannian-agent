package com.wannian.server.app.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** 0.2.4-C：临时事件扇出；多订阅者各自收到（SSE 去重须按连接隔离，总线本身不吞事件）。 */
class RunEventBusTest {

    @Test
    void twoSubscribersEachReceivePublishedEvents() {
        RunEventBus bus = new RunEventBus(64);
        String cid = "conv-a";
        List<RunEvent> a = new CopyOnWriteArrayList<>();
        List<RunEvent> b = new CopyOnWriteArrayList<>();
        Runnable ua = bus.subscribe(cid, a::add);
        Runnable ub = bus.subscribe(cid, b::add);

        RunEvent delta =
                RunEvent.replyDelta(cid, "turn-1", "exec-1", 1L, "你好");
        bus.publish(delta);

        assertThat(a).containsExactly(delta);
        assertThat(b).containsExactly(delta);

        ua.run();
        ub.run();
    }

    @Test
    void recentReplayVisibleToLateSubscriber() {
        RunEventBus bus = new RunEventBus(64);
        String cid = "conv-b";
        bus.publish(RunEvent.turnStarted(cid, "t1", "e1", 1L));
        bus.publish(RunEvent.replyDelta(cid, "t1", "e1", 2L, "hi"));

        List<RunEvent> late = new ArrayList<>(bus.recent(cid));
        assertThat(late).hasSize(2);
        assertThat(late.get(0).type()).isEqualTo("turn.started");
        assertThat(late.get(1).type()).isEqualTo("reply.delta");
    }
}
