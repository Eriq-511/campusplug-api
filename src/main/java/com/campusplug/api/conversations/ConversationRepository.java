package com.campusplug.api.conversations;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {

    Optional<ConversationEntity> findByListingIdAndInquirerUserIdAndPosterUserId(Long listingId, Long inquirerUserId, Long posterUserId);

    // NEW: Find conversation between any two participants (regardless of listings)
    @Query(
            value = """
                select c.*
                from conversations c
                where (c.inquirer_user_id = :user1 and c.poster_user_id = :user2)
                   or (c.inquirer_user_id = :user2 and c.poster_user_id = :user1)
                order by c.created_at asc
                limit 1
                """,
            nativeQuery = true
    )
    Optional<ConversationEntity> findByParticipants(@Param("user1") Long user1, @Param("user2") Long user2);

    interface ConversationListItemProjection {
        Long getId();

        Long getListingId();

        String getListingTitle();

        Long getCounterpartUserId();

        String getCounterpartFullName();

        String getCounterpartEmail();

        String getCounterpartPhoneNumber();

        String getCounterpartLocationText();

        String getLastMessageBody();

        Instant getLastMessageAt();
    }

    @Query(
            value = """
                select
                  c.id as id,
                  c.listing_id as listingId,
                  l.title as listingTitle,
                  case when c.inquirer_user_id = :userId then c.poster_user_id else c.inquirer_user_id end as counterpartUserId,
                  u.full_name as counterpartFullName,
                  u.email as counterpartEmail,
                  u.phone_number as counterpartPhoneNumber,
                  coalesce(u.registered_location_text, u.alternate_location_text) as counterpartLocationText,
                  m.body as lastMessageBody,
                  m.created_at as lastMessageAt
                from conversations c
                join listings l on l.id = c.listing_id
                join users u on u.id = (case when c.inquirer_user_id = :userId then c.poster_user_id else c.inquirer_user_id end)
                left join lateral (
                  select body, created_at
                  from messages
                  where conversation_id = c.id
                  order by id desc
                  limit 1
                ) m on true
                where c.inquirer_user_id = :userId or c.poster_user_id = :userId
                order by coalesce(m.created_at, c.updated_at) desc, c.id desc
                """,
            countQuery = """
                select count(*)
                from conversations c
                where c.inquirer_user_id = :userId or c.poster_user_id = :userId
                """,
            nativeQuery = true
    )
    Page<ConversationListItemProjection> findConversationList(@Param("userId") Long userId, Pageable pageable);

    @Query(
            value = """
                select c.*
                from conversations c
                where c.id = :conversationId
                  and (c.inquirer_user_id = :userId or c.poster_user_id = :userId)
                """,
            nativeQuery = true
    )
    Optional<ConversationEntity> findParticipantConversation(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Transactional
    @Modifying
    @Query(value = "update conversations set updated_at = now() where id = :conversationId", nativeQuery = true)
    int touchUpdatedAt(@Param("conversationId") Long conversationId);
}
