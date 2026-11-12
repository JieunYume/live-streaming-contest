package com.livecraft.comment;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    @Query("select c from Comment c join fetch c.author where c.broadcast.id = :broadcastId order by c.id desc")
    List<Comment> findRecent(Long broadcastId, Pageable pageable);
}
