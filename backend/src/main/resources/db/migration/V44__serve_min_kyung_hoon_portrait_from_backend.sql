-- Serve the verified CC BY-SA 2.0 portrait from our own API host. Android clients
-- have previously failed to load Wikimedia-hosted artist images.
UPDATE artist_portrait
SET image_url = 'https://j15b110.p.ssafy.io/api/v1/artist-portraits/min-kyung-hoon.jpg'
WHERE normalized_name IN ('민경훈', 'min kyung-hoon')
  AND image_url = 'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d8/Min_Kyung-Hoon.jpg/500px-Min_Kyung-Hoon.jpg';
