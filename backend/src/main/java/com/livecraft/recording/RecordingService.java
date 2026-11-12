package com.livecraft.recording;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.livecraft.auth.AuthUser;
import com.livecraft.broadcast.Broadcast;
import com.livecraft.broadcast.BroadcastEndedEvent;
import com.livecraft.broadcast.BroadcastService;
import com.livecraft.global.AppProperties;
import com.livecraft.global.Tokens;
import com.livecraft.global.error.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * 녹화: 시작 → 조각 업로드(순서 번호) → 종료. 방송이 끝나면 진행 중인 녹화도 자동으로 마무리된다.
 * shortcut: 파일을 서버 로컬 디스크에 저장. 배포 시 Cloudflare R2 같은 객체 저장소로 바꾼다 (filePath 는 그대로 상대 경로).
 */
@Service
@RequiredArgsConstructor
public class RecordingService {

    private static final List<String> ALLOWED_MIME_PREFIX = List.of("video/webm", "video/mp4");

    private final RecordingRepository recordingRepository;
    private final BroadcastService broadcastService;
    private final AppProperties props;

    public record RecordingDto(Long id, Long broadcastId, String title, String ownerNickname, Recording.Status status,
                               String mimeType, long sizeBytes, Instant startedAt, Instant endedAt) {
        static RecordingDto from(Recording r) {
            Broadcast b = r.getBroadcast();
            return new RecordingDto(r.getId(), b.getId(), b.getTitle(), b.getOwner().getNickname(), r.getStatus(),
                    r.getMimeType(), r.getSizeBytes(), r.getStartedAt(), r.getEndedAt());
        }
    }

    public record VideoFile(Path path, String mimeType) {}

    @Transactional
    public RecordingDto start(AuthUser me, Long broadcastId, String mimeType) {
        Broadcast b = broadcastService.find(broadcastId);
        if (!b.isOwnedBy(me.id())) throw ApiException.forbidden("방송 소유자만 녹화할 수 있습니다.");
        if (b.getStatus() != Broadcast.Status.LIVE) throw ApiException.conflict("NOT_LIVE", "방송 중일 때만 녹화할 수 있습니다.");
        String mime = mimeType == null ? "video/webm" : mimeType;
        if (ALLOWED_MIME_PREFIX.stream().noneMatch(mime::startsWith)) {
            throw ApiException.badRequest("UNSUPPORTED_FORMAT", "지원하지 않는 영상 형식입니다.");
        }
        String ext = mime.startsWith("video/mp4") ? ".mp4" : ".webm";
        String relative = "broadcast-" + b.getId() + "/" + Tokens.random(9) + ext;
        Recording r = recordingRepository.save(new Recording(b, relative, mime));
        return RecordingDto.from(r);
    }

    /**
     * 조각 하나를 파일 끝에 붙인다.
     * - seq 가 기대값보다 작으면 이미 받은 조각의 재전송 → 무시하고 성공 처리 (중복 방지)
     * - 기대값보다 크면 앞 조각이 빠진 것 → 409 로 알려 클라이언트가 순서대로 다시 보내게 한다
     */
    @Transactional
    public void appendChunk(AuthUser me, Long recordingId, int seq, byte[] data) {
        Recording r = recordingRepository.findForUpdate(recordingId).orElseThrow(() -> ApiException.notFound("녹화"));
        if (!r.getBroadcast().isOwnedBy(me.id())) throw ApiException.forbidden("방송 소유자만 업로드할 수 있습니다.");
        if (r.getStatus() != Recording.Status.RECORDING) throw ApiException.conflict("RECORDING_FINISHED", "이미 끝난 녹화입니다.");
        if (seq < r.getNextSeq()) return;
        if (seq > r.getNextSeq()) {
            throw new ApiException(HttpStatus.CONFLICT, "CHUNK_OUT_OF_ORDER", "다음 조각 번호는 " + r.getNextSeq() + " 입니다.");
        }
        Path file = resolve(r.getFilePath());
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("녹화 조각 저장 실패", e);
        }
        r.appended(data.length);
    }

    @Transactional
    public RecordingDto finish(AuthUser me, Long recordingId) {
        Recording r = recordingRepository.findForUpdate(recordingId).orElseThrow(() -> ApiException.notFound("녹화"));
        if (!r.getBroadcast().isOwnedBy(me.id())) throw ApiException.forbidden("방송 소유자만 녹화를 끝낼 수 있습니다.");
        r.finish();
        return RecordingDto.from(r);
    }

    @EventListener
    @Transactional
    public void onBroadcastEnded(BroadcastEndedEvent event) {
        recordingRepository.findByBroadcastIdAndStatus(event.broadcastId(), Recording.Status.RECORDING).forEach(Recording::finish);
    }

    @Transactional(readOnly = true)
    public List<RecordingDto> list() {
        return recordingRepository.findReady().stream().map(RecordingDto::from).toList();
    }

    @Transactional(readOnly = true)
    public RecordingDto get(Long id) {
        return recordingRepository.findWithBroadcast(id).map(RecordingDto::from).orElseThrow(() -> ApiException.notFound("녹화"));
    }

    @Transactional(readOnly = true)
    public VideoFile video(Long id) {
        Recording r = recordingRepository.findWithBroadcast(id)
                .filter(x -> x.getStatus() == Recording.Status.READY)
                .orElseThrow(() -> ApiException.notFound("녹화"));
        Path file = resolve(r.getFilePath());
        if (!Files.exists(file)) throw ApiException.notFound("녹화 파일");
        return new VideoFile(file, r.getMimeType());
    }

    private Path resolve(String relative) {
        Path base = Path.of(props.recording().dir()).toAbsolutePath().normalize();
        Path file = base.resolve(relative).normalize();
        if (!file.startsWith(base)) throw new IllegalStateException("잘못된 녹화 경로");
        return file;
    }
}
