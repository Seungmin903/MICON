import { index, integer, sqliteTable, text } from 'drizzle-orm/sqlite-core';

export const analysisJobs = sqliteTable('analysis_jobs', {
  id: text('id').primaryKey(),
  originalName: text('original_name').notNull(),
  objectKey: text('object_key').notNull(),
  contentType: text('content_type').notNull(),
  sizeBytes: integer('size_bytes').notNull(),
  sourceType: text('source_type').notNull(),
  status: text('status').notNull(),
  progress: integer('progress').notNull().default(0),
  errorMessage: text('error_message'),
  createdAt: text('created_at').notNull(),
  updatedAt: text('updated_at').notNull(),
}, (table) => [
  index('idx_analysis_jobs_created_at').on(table.createdAt),
]);
