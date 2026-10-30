CREATE INDEX idx_game_player_time_class_played_at
    ON game (player_id, time_class, played_at);

CREATE INDEX idx_move_game_player_move_ply
    ON move (game_id, is_player_move, ply);
