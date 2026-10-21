package com.kuronami.questtree.api;

import com.google.gson.JsonParser;
import com.kuronami.questtree.server.ProgressValidator;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The addon-facing registry as the validator sees it: a registered type is not reported, an absent one is. */
class QuestTreeApiTest {

    private static final ResourceLocation TASK = ResourceLocation.fromNamespaceAndPath("questtree_test", "counter");
    private static final ResourceLocation REWARD = ResourceLocation.fromNamespaceAndPath("questtree_test", "prize");
    private static final ResourceLocation CONDITION = ResourceLocation.fromNamespaceAndPath("questtree_test", "enabled");

    private static ProgressValidator.Result validate(String type, String condition) {
        var bundle = JsonParser.parseString("""
                {"chapters": {"t:api": {"nodes": {"q": {
                  "tasks": [{"type": "%s", "count": 1, "conditions": [{"type": "%s"}]}],
                  "rewards": [{"type": "%s", "conditions": [{"type": "%s"}]}]
                }}}}}""".formatted(type, condition, REWARD, condition)).getAsJsonObject();
        return ProgressValidator.validate(bundle, Map.of());
    }

    @Test
    void registeredTypesAndConditionsAreAccepted() {
        QuestTreeApi.registerTaskType(TASK, context -> context.currentProgress());
        QuestTreeApi.registerRewardType(REWARD, context -> {
        });
        QuestTreeApi.registerConditionType(CONDITION, context -> true);

        ProgressValidator.Result result = validate(TASK.toString(), CONDITION.toString());
        assertTrue(QuestTreeApi.taskTypeRegistered(TASK.toString()));
        assertTrue(QuestTreeApi.rewardTypeRegistered(REWARD.toString()));
        assertFalse(QuestTreeApi.conditionTypeRegistered("questtree_test:missing"));
        assertEquals(0, result.errors(), result.problems.toString());
        assertEquals(0, result.warnings(), result.problems.toString());
    }

    @Test
    void aScriptCannotTakeOverAJavaType() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("questtree_test", "script_dup");
        QuestTaskType javaHandler = context -> 7;
        QuestTreeApi.registerTaskType(id, javaHandler);

        assertFalse(QuestTreeApi.registerTaskType(id.toString(), context -> 8),
                "a script registration is refused instead of replacing Java's handler");
        assertSame(javaHandler, QuestTreeApi.taskType(id.toString()));
        assertTrue(QuestTreeApi.unregisterTaskType(id.toString()), "the script reload can still forget its own id");
    }

    @Test
    void anAbsentAddonIsReportedButDoesNotDropTheNode() {
        ProgressValidator.Result result = validate("questtree_test:missing_task", "questtree_test:missing");
        assertEquals(1, result.errors(), "an unknown task type is an error, as before");
        assertTrue(result.problems.stream().anyMatch(p -> p.message().contains("not registered")),
                result.problems.toString());
        assertTrue(result.droppedNodes.contains("t:api/q"));
    }
}
