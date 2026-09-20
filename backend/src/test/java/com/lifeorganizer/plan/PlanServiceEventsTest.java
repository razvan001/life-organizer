package com.lifeorganizer.plan;

import com.lifeorganizer.config.EngineConfig;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.engine.AddAdhoc;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.engine.MarkDone;
import com.lifeorganizer.engine.PostponeBlock;
import com.lifeorganizer.error.SetupRequiredException;
import com.lifeorganizer.error.UnknownBlockException;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.support.ExampleSetup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PlanService.class, SetupStore.class, PlanStore.class, EngineConfig.class})
class PlanServiceEventsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final LocalDate MON = ExampleSetup.MONDAY;
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate SUN = MON.plusDays(6);

    @Autowired
    PlanService planService;

    @Autowired
    SetupStore setupStore;

    @Autowired
    PlanStore planStore;

    @PersistenceContext
    EntityManager entityManager;

    private void saveExampleSetup() {
        setupStore.replace(ExampleSetup.tree(), ExampleSetup.constraints());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private String idOf(DayPlan plan, String title) {
        return plan.blocks().stream()
            .filter(b -> b.title().equals(title) && b.status() == BlockStatus.PLANNED)
            .findFirst().orElseThrow().id();
    }

    private Block block(DayPlan plan, String id) {
        return plan.blocks().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void markDoneIsPersisted() {
        saveExampleSetup();
        String gymId = idOf(planService.getOrGenerate(MON), "Gym");

        AppliedEvent applied = planService.applyEvent(new MarkDone(gymId), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Marked done Gym");
        assertThat(block(applied.today(), gymId).status()).isEqualTo(BlockStatus.DONE);
        assertThat(block(planStore.findDay(MON).orElseThrow(), gymId).status()).isEqualTo(BlockStatus.DONE);
    }

    @Test
    void cancelPersistsTheFreedSlot() {
        saveExampleSetup();
        String gymId = idOf(planService.getOrGenerate(MON), "Gym");

        AppliedEvent applied = planService.applyEvent(new CancelBlock(gymId), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Cancelled Gym - slot is now free");
        DayPlan reloaded = planStore.findDay(MON).orElseThrow();
        assertThat(block(reloaded, gymId).status()).isEqualTo(BlockStatus.CANCELLED);
        Block freed = block(reloaded, gymId + "-freed");
        assertThat(freed.kind()).isEqualTo(BlockKind.FREE);
        assertThat(freed.start()).isEqualTo(LocalTime.of(7, 0));
        assertThat(freed.end()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void postponeMovesTheBlockToALaterFreeSlotOnTheSameDay() {
        saveExampleSetup();
        String certId = idOf(planService.getOrGenerate(MON), "Claude certification");

        AppliedEvent applied = planService.applyEvent(new PostponeBlock(certId, "tired"), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Moved Claude certification 18:00 -> 2026-09-14 21:00");
        DayPlan reloaded = planStore.findDay(MON).orElseThrow();

        Block original = block(reloaded, certId);
        assertThat(original.status()).isEqualTo(BlockStatus.POSTPONED);
        assertThat(original.reason()).isEqualTo("tired");

        Block moved = block(reloaded, certId + "-p1");
        assertThat(moved.status()).isEqualTo(BlockStatus.PLANNED);
        assertThat(moved.start()).isEqualTo(LocalTime.of(21, 0));
        assertThat(moved.end()).isEqualTo(LocalTime.of(22, 0));
        assertThat(moved.postponeCount()).isEqualTo(1);

        // the free slot it used was split: the consumed block is gone, the remainder is kept
        assertThat(reloaded.blocks()).noneMatch(b -> b.id().equals("free-2026-09-14-21:00"));
        Block rest = block(reloaded, "free-2026-09-14-21:00-rest");
        assertThat(rest.start()).isEqualTo(LocalTime.of(22, 0));
        assertThat(rest.end()).isEqualTo(LocalTime.of(22, 30));
    }

    @Test
    void postponingTwiceMovesTheBlockToTheNextDayAndPersistsBothDays() {
        saveExampleSetup();
        String certId = idOf(planService.getOrGenerate(MON), "Claude certification");
        planService.applyEvent(new PostponeBlock(certId, "tired"), MON);
        String firstMoveId = certId + "-p1";

        // A postpone looks at the rest of the week for room, so it generates the later days.
        assertThat(planStore.findDay(SUN)).isPresent();

        AppliedEvent applied = planService.applyEvent(new PostponeBlock(firstMoveId, "still tired"), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Moved Claude certification 21:00 -> 2026-09-15 19:00");

        DayPlan monday = planStore.findDay(MON).orElseThrow();
        assertThat(block(monday, firstMoveId).status()).isEqualTo(BlockStatus.POSTPONED);

        DayPlan tuesday = planStore.findDay(TUE).orElseThrow();
        Block secondMove = block(tuesday, firstMoveId + "-p2");
        assertThat(secondMove.start()).isEqualTo(LocalTime.of(19, 0));
        assertThat(secondMove.end()).isEqualTo(LocalTime.of(20, 0));
        assertThat(secondMove.postponeCount()).isEqualTo(2);
        assertThat(secondMove.flagged()).isFalse();
        assertThat(tuesday.blocks()).noneMatch(b -> b.id().equals("free-2026-09-15-19:00"));
        assertThat(block(tuesday, "free-2026-09-15-19:00-rest").start()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void theThirdPostponeFlagsTheBlock() {
        saveExampleSetup();
        String certId = idOf(planService.getOrGenerate(MON), "Claude certification");
        planService.applyEvent(new PostponeBlock(certId, "1"), MON);
        planService.applyEvent(new PostponeBlock(certId + "-p1", "2"), MON);

        planService.applyEvent(new PostponeBlock(certId + "-p1-p2", "3"), MON);
        flushAndClear();

        Block third = block(planStore.findDay(TUE).orElseThrow(), certId + "-p1-p2-p3");
        assertThat(third.postponeCount()).isEqualTo(3);
        assertThat(third.flagged()).isTrue();
        assertThat(third.start()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void postponeRefusesAFixedBlockAndChangesNothing() {
        saveExampleSetup();
        String workId = idOf(planService.getOrGenerate(MON), "Work");

        AppliedEvent applied = planService.applyEvent(new PostponeBlock(workId, "nope"), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Cannot postpone a FIXED block (Work)");
        Block work = block(planStore.findDay(MON).orElseThrow(), workId);
        assertThat(work.status()).isEqualTo(BlockStatus.PLANNED);
        assertThat(work.start()).isEqualTo(LocalTime.of(8, 30));
    }

    @Test
    void postponeRefusesACompletedBlock() {
        saveExampleSetup();
        String certId = idOf(planService.getOrGenerate(MON), "Claude certification");
        planService.applyEvent(new MarkDone(certId), MON);

        AppliedEvent applied = planService.applyEvent(new PostponeBlock(certId, "x"), MON);

        assertThat(applied.changes()).containsExactly("Cannot postpone a completed block (Claude certification)");
    }

    @Test
    void postponingAnAlreadyPostponedBlockIsRefused() {
        saveExampleSetup();
        String certId = idOf(planService.getOrGenerate(MON), "Claude certification");
        planService.applyEvent(new PostponeBlock(certId, "tired"), MON);

        AppliedEvent applied = planService.applyEvent(new PostponeBlock(certId, "again"), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Cannot postpone a postponed block (Claude certification)");
        DayPlan monday = planStore.findDay(MON).orElseThrow();
        assertThat(monday.blocks()).filteredOn(b -> b.id().equals(certId + "-p1")).hasSize(1);
        Block moved = block(monday, certId + "-p1");
        assertThat(moved.status()).isEqualTo(BlockStatus.PLANNED);
        assertThat(moved.start()).isEqualTo(LocalTime.of(21, 0));
        Block rest = block(monday, "free-2026-09-14-21:00-rest");
        assertThat(rest.kind()).isEqualTo(BlockKind.FREE);
        assertThat(rest.start()).isEqualTo(LocalTime.of(22, 0));
        assertThat(rest.end()).isEqualTo(LocalTime.of(22, 30));
    }

    @Test
    void anUnknownBlockIdIsRejected() {
        saveExampleSetup();
        planService.getOrGenerate(MON);

        assertThatThrownBy(() -> planService.applyEvent(new CancelBlock("nope"), MON))
            .isInstanceOf(UnknownBlockException.class)
            .hasMessageContaining("nope");
    }

    @Test
    void eventsThatDoNotNeedRoomDoNotGenerateOtherDays() {
        saveExampleSetup();
        String gymId = idOf(planService.getOrGenerate(MON), "Gym");

        planService.applyEvent(new MarkDone(gymId), MON);

        assertThat(planStore.findDay(TUE)).isEmpty();
    }

    @Test
    void addAdhocOnADayThatIsNotGeneratedYetGeneratesItAndTakesOverItsFreeTime() {
        saveExampleSetup();

        AppliedEvent applied = planService.applyEvent(
            new AddAdhoc(TUE, "Padel", LocalTime.of(21, 0), LocalTime.of(22, 30)), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Added Padel at 21:00");
        assertThat(applied.today().date()).isEqualTo(MON);

        DayPlan tuesday = planStore.findDay(TUE).orElseThrow();
        Block padel = block(tuesday, "adhoc-2026-09-15-21:00");
        assertThat(padel.kind()).isEqualTo(BlockKind.ADHOC);
        // Tuesday's free block 19:00-22:30 lost the 21:00-22:30 it overlaps; 19:00-21:00 is left.
        assertThat(tuesday.blocks()).noneMatch(b -> b.id().equals("free-2026-09-15-19:00"));
        Block before = block(tuesday, "free-2026-09-15-19:00-before");
        assertThat(before.start()).isEqualTo(LocalTime.of(19, 0));
        assertThat(before.end()).isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    void eventsBeforeAnySetupAreRejected() {
        assertThatThrownBy(() -> planService.applyEvent(
            new AddAdhoc(MON, "Padel", LocalTime.of(21, 0), LocalTime.of(22, 0)), MON))
            .isInstanceOf(SetupRequiredException.class);
    }
}
