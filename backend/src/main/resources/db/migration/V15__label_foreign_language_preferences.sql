-- Preferences group all English/Japanese songs by language, regardless of style.
-- Keep IDs and import keys so existing selections and catalog imports still work.
UPDATE genre SET preference_label = '영어곡' WHERE normalized_name = 'global-pop';
UPDATE genre SET preference_label = '일본곡' WHERE normalized_name = 'etc-jpop';
