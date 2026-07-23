package com.auraboot.framework.scheduler.xxl;

import com.auraboot.framework.scheduler.entity.ScheduledTask;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledTaskXxlMetadataTest {

    @Test
    void scheduledTaskEntity_exposesExternalSchedulerMetadata() {
        ScheduledTask task = new ScheduledTask();

        task.setSchedulerType("xxl");
        task.setExternalJobId("42");
        task.setExternalExecutorApp("auraboot-platform");
        task.setExternalSyncStatus("active");
        task.setExternalSyncError("none");
        task.setRouteStrategy("FIRST");
        task.setBlockStrategy("SERIAL_EXECUTION");
        task.setMisfireStrategy("DO_NOTHING");
        task.setShardingEnabled(true);

        assertThat(task.getSchedulerType()).isEqualTo("xxl");
        assertThat(task.getExternalJobId()).isEqualTo("42");
        assertThat(task.getExternalExecutorApp()).isEqualTo("auraboot-platform");
        assertThat(task.getExternalSyncStatus()).isEqualTo("active");
        assertThat(task.getExternalSyncError()).isEqualTo("none");
        assertThat(task.getRouteStrategy()).isEqualTo("FIRST");
        assertThat(task.getBlockStrategy()).isEqualTo("SERIAL_EXECUTION");
        assertThat(task.getMisfireStrategy()).isEqualTo("DO_NOTHING");
        assertThat(task.getShardingEnabled()).isTrue();
    }

    @Test
    void schema_containsExternalSchedulerMetadataColumns() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/snapshots/schema-current.sql"));

        assertThat(schema).contains("scheduler_type");
        assertThat(schema).contains("external_job_id");
        assertThat(schema).contains("external_executor_app");
        assertThat(schema).contains("external_sync_status");
        assertThat(schema).contains("external_sync_error");
        assertThat(schema).contains("route_strategy");
        assertThat(schema).contains("block_strategy");
        assertThat(schema).contains("misfire_strategy");
        assertThat(schema).contains("sharding_enabled");
    }
}
