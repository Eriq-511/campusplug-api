package com.campusplug.api.messages;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    @Query(
            value = """
                select *
                from messages
                where conversation_id = :conversationId
                  and id > :afterMessageId
                order by id asc
                """,
            nativeQuery = true
    )
    List<MessageEntity> findNewMessages(
            @Param("conversationId") Long conversationId,
            @Param("afterMessageId") Long afterMessageId,
            Pageable pageable);

    @Query(
            value = """
                select *
                from messages
                where conversation_id = :conversationId
                order by id desc
                """,
            nativeQuery = true
    )
    List<MessageEntity> findLatestMessages(@Param("conversationId") Long conversationId, Pageable pageable);
}
