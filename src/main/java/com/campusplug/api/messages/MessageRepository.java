package com.campusplug.api.messages;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

        interface MessageRowProjection {
                Long getId();

                Long getConversationId();

                Long getSenderUserId();

                String getBody();

                Long getReferencedListingId();

                java.time.Instant getCreatedAt();
        }

    @Query(
            value = """
                                select
                                    m.id as id,
                                    m.conversation_id as conversationId,
                                    m.sender_user_id as senderUserId,
                                    m.body as body,
                                    m.referenced_listing_id as referencedListingId,
                                    m.created_at as createdAt
                                from messages m
                                where m.conversation_id = :conversationId
                                    and m.id > :afterMessageId
                                order by m.id asc
                                limit :limit
                """,
            nativeQuery = true
    )
        List<MessageRowProjection> findNewMessages(
            @Param("conversationId") Long conversationId,
            @Param("afterMessageId") Long afterMessageId,
                        @Param("limit") int limit);

    @Query(
            value = """
                                select
                                    m.id as id,
                                    m.conversation_id as conversationId,
                                    m.sender_user_id as senderUserId,
                                    m.body as body,
                                    m.referenced_listing_id as referencedListingId,
                                    m.created_at as createdAt
                                from (
                                    select
                                        id,
                                        conversation_id,
                                        sender_user_id,
                                        body,
                                        referenced_listing_id,
                                        created_at
                                    from messages
                                    where conversation_id = :conversationId
                                    order by id desc
                                    limit :limit
                                ) m
                                order by m.id asc
                """,
            nativeQuery = true
    )
        List<MessageRowProjection> findLatestMessages(
                        @Param("conversationId") Long conversationId,
                        @Param("limit") int limit);
}
