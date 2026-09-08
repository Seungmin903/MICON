import { env } from 'cloudflare:workers';
import { ensureDatabase } from '../../../db/init';

export const runtime = 'edge';

const MAX_FILE_SIZE = 50 * 1024 * 1024;
const AUDIO_TYPES = new Set([
  'audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/x-wav',
  'audio/flac', 'audio/mp4', 'audio/x-m4a',
]);

function safeExtension(name: string) {
  const extension = name.split('.').pop()?.toLowerCase().replace(/[^a-z0-9]/g, '');
  return extension && extension.length <= 5 ? extension : 'audio';
}

export async function POST(request: Request) {
  try {
    const form = await request.formData();
    const audio = form.get('audio');
    const sourceType = form.get('sourceType') === 'vocal' ? 'vocal' : 'mix';

    if (!(audio instanceof File)) {
      return Response.json({ error: '음원 파일을 선택해주세요.' }, { status: 400 });
    }
    if (audio.size === 0 || audio.size > MAX_FILE_SIZE) {
      return Response.json({ error: '파일은 50MB 이하만 업로드할 수 있습니다.' }, { status: 413 });
    }
    if (audio.type && !AUDIO_TYPES.has(audio.type)) {
      return Response.json({ error: 'MP3, WAV, FLAC 또는 M4A 음원만 지원합니다.' }, { status: 415 });
    }

    await ensureDatabase();
    const id = crypto.randomUUID();
    const now = new Date().toISOString();
    const objectKey = `uploads/${id}/original.${safeExtension(audio.name)}`;

    await env.FILES.put(objectKey, await audio.arrayBuffer(), {
      httpMetadata: { contentType: audio.type || 'application/octet-stream' },
      customMetadata: { originalName: encodeURIComponent(audio.name), sourceType },
    });

    const status = sourceType === 'vocal' ? 'ready_for_local_analysis' : 'queued_for_separation';
    const progress = sourceType === 'vocal' ? 65 : 15;
    await env.DB.prepare(`
      INSERT INTO analysis_jobs (
        id, original_name, object_key, content_type, size_bytes,
        source_type, status, progress, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(id, audio.name, objectKey, audio.type || 'application/octet-stream', audio.size,
      sourceType, status, progress, now, now).run();

    return Response.json({
      id,
      status,
      message: sourceType === 'vocal'
        ? '업로드가 완료되었습니다. 브라우저 음역 분석을 시작합니다.'
        : '업로드가 완료되었습니다. AI 보컬 분리 작업을 기다리고 있습니다.',
    }, { status: 201 });
  } catch (error) {
    console.error('Upload failed', error);
    return Response.json({ error: '업로드 중 오류가 발생했습니다.' }, { status: 500 });
  }
}
