-- Minimal seed data for demos.
INSERT INTO users (country, signup_date)
SELECT
  (ARRAY['US','CA','IN','GB','DE'])[(random()*4)::int + 1] AS country,
  (CURRENT_DATE - ((random()*365)::int))::date AS signup_date
FROM generate_series(1,200) s;

INSERT INTO events (user_id, event_name, created_at)
SELECT
  (random()*199)::int + 1 AS user_id,
  (ARRAY['open_app','purchase','view_item','add_to_cart'])[(random()*3)::int + 1] AS event_name,
  NOW() - ((random()*90)::int || ' days')::interval - ((random()*86400)::int || ' seconds')::interval
FROM generate_series(1,3000) s;

INSERT INTO subscriptions (user_id, status, mrr)
SELECT
  u.id,
  (CASE WHEN random() < 0.6 THEN 'active' ELSE 'canceled' END) AS status,
  (CASE WHEN random() < 0.7 THEN 9.99 ELSE 19.99 END)::numeric(10,2) AS mrr
FROM users u
WHERE u.id % 3 = 0;

INSERT INTO sessions (user_id, started_at, device_type)
SELECT
  (random()*199)::int + 1 AS user_id,
  NOW() - ((random()*60)::int || ' days')::interval,
  (ARRAY['ios','android','web'])[(random()*2)::int + 1] AS device_type
FROM generate_series(1,1500) s;
