import { env } from 'cloudflare:workers';
import { ensureDatabase } from '../../../../db/init';

export const runtime = 'edge';
type RouteContext = { params: Promise<{ id: string }> };

export async function GET(_request: Request, context: RouteContext) {
  const { id } = await context.params;
  if (!/^[0-9a-f-]{36}$/i.test(id)) {
    return Response.json({ error: '잘못된 작업 번호입니다.' }, { status: 400 });
  }

  await ensureDatabase();
  const job = await env.DB.prepare(`
    SELECT id, original_name AS originalName, source_type AS sourceType,
           status, progress, error_message AS errorMessage,
           created_at AS createdAt, updated_at AS updatedAt
    FROM analysis_jobs WHERE id = ?
  `).bind(id).first();

  if (!job) return Response.json({ error: '작업을 찾을 수 없습니다.' }, { status: 404 });
  return Response.json(job);
}
