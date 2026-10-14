package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchedulePauseEntityRepository extends JpaRepository<SchedulePauseEntity, String> {
}
