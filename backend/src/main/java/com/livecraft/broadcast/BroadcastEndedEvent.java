package com.livecraft.broadcast;

/** 방송이 종료됐을 때 (녹화 마무리 등 다른 기능이 반응한다) */
public record BroadcastEndedEvent(Long broadcastId) {}
