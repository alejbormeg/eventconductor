package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CronScheduleEntityRepository extends JpaRepository<CronScheduleEntity, String> {

    @Modifying
    @Query("delete from CronScheduleEntity c where c.sourceKey = :sourceKey")
    void deleteBySourceKey(@Param("sourceKey") String sourceKey);

    @Modifying
    @Query("delete from CronScheduleEntity c where c.sourceKey = :sourceKey and c.scheduleId in :scheduleIds")
    void deleteBySourceKeyAndScheduleIdIn(@Param("sourceKey") String sourceKey,
                                          @Param("scheduleIds") java.util.Collection<String> scheduleIds);
}
