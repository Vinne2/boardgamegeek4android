package com.boardgamegeek.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AlertDialog.Builder;
import android.support.v7.graphics.Palette;
import android.support.v7.graphics.Palette.Swatch;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.boardgamegeek.R;
import com.boardgamegeek.auth.Authenticator;
import com.boardgamegeek.events.CollectionItemUpdatedEvent;
import com.boardgamegeek.events.GameInfoChangedEvent;
import com.boardgamegeek.io.Adapter;
import com.boardgamegeek.io.BggService;
import com.boardgamegeek.model.Forum;
import com.boardgamegeek.model.ForumListResponse;
import com.boardgamegeek.provider.BggContract.Collection;
import com.boardgamegeek.provider.BggContract.GamePollResultsResult;
import com.boardgamegeek.provider.BggContract.GamePolls;
import com.boardgamegeek.provider.BggContract.GameSuggestedPlayerCountPollPollResults;
import com.boardgamegeek.provider.BggContract.Games;
import com.boardgamegeek.provider.BggContract.Plays;
import com.boardgamegeek.service.SyncService;
import com.boardgamegeek.tasks.AddCollectionItemTask;
import com.boardgamegeek.tasks.FavoriteGameTask;
import com.boardgamegeek.tasks.sync.SyncCollectionByGameTask;
import com.boardgamegeek.tasks.sync.SyncGameTask;
import com.boardgamegeek.tasks.sync.SyncPlaysByGameTask;
import com.boardgamegeek.ui.adapter.GameColorAdapter;
import com.boardgamegeek.ui.dialog.CollectionStatusDialogFragment;
import com.boardgamegeek.ui.dialog.CollectionStatusDialogFragment.CollectionStatusDialogListener;
import com.boardgamegeek.ui.dialog.GameUsersDialogFragment;
import com.boardgamegeek.ui.dialog.RanksFragment;
import com.boardgamegeek.ui.model.Game;
import com.boardgamegeek.ui.model.GameArtist;
import com.boardgamegeek.ui.model.GameBaseGame;
import com.boardgamegeek.ui.model.GameCategory;
import com.boardgamegeek.ui.model.GameDesigner;
import com.boardgamegeek.ui.model.GameExpansion;
import com.boardgamegeek.ui.model.GameList;
import com.boardgamegeek.ui.model.GameMechanic;
import com.boardgamegeek.ui.model.GamePublisher;
import com.boardgamegeek.ui.model.GameRank;
import com.boardgamegeek.ui.widget.GameCollectionRow;
import com.boardgamegeek.ui.widget.GameDetailRow;
import com.boardgamegeek.ui.widget.SafeViewTarget;
import com.boardgamegeek.ui.widget.TimestampView;
import com.boardgamegeek.util.ActivityUtils;
import com.boardgamegeek.util.ColorUtils;
import com.boardgamegeek.util.CursorUtils;
import com.boardgamegeek.util.DateTimeUtils;
import com.boardgamegeek.util.DialogUtils;
import com.boardgamegeek.util.HelpUtils;
import com.boardgamegeek.util.PaletteUtils;
import com.boardgamegeek.util.PlayerCountRecommendation;
import com.boardgamegeek.util.PreferencesUtils;
import com.boardgamegeek.util.PresentationUtils;
import com.boardgamegeek.util.ScrimUtils;
import com.boardgamegeek.util.SelectionBuilder;
import com.boardgamegeek.util.ShowcaseViewWizard;
import com.boardgamegeek.util.StringUtils;
import com.boardgamegeek.util.TaskUtils;
import com.boardgamegeek.util.UIUtils;
import com.github.amlcurran.showcaseview.targets.Target;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import butterknife.BindView;
import butterknife.BindViews;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.Unbinder;
import hugo.weaving.DebugLog;
import icepick.Icepick;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import timber.log.Timber;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

public class GameFragment extends Fragment implements LoaderCallbacks<Cursor> {
	private static final int HELP_VERSION = 2;
	private static final int AGE_IN_DAYS_TO_REFRESH = 7;
	private static final int SYNC_NONE = 0;
	private static final int SYNC_GAME = 1;
	private static final int SYNC_PLAYS = 1 << 1;
	private static final int SYNC_COLLECTION = 1 << 2;

	private static final int GAME_TOKEN = 0x11;
	private static final int DESIGNER_TOKEN = 0x12;
	private static final int ARTIST_TOKEN = 0x13;
	private static final int PUBLISHER_TOKEN = 0x14;
	private static final int CATEGORY_TOKEN = 0x15;
	private static final int MECHANIC_TOKEN = 0x16;
	private static final int EXPANSION_TOKEN = 0x17;
	private static final int BASE_GAME_TOKEN = 0x18;
	private static final int RANK_TOKEN = 0x19;

	private Uri gameUri;
	private String gameName;
	private String imageUrl;
	private String thumbnailUrl;
	private boolean arePlayersCustomSorted;
	private boolean mightNeedRefreshing;
	private int isRefreshing;

	private Unbinder unbinder;

	@BindView(R.id.game_rating) TextView ratingView;
	@BindView(R.id.game_description) TextView descriptionView;
	@BindView(R.id.game_year_published) TextView yearPublishedView;

	@BindView(R.id.number_of_players) TextView numberOfPlayersView;
	@BindView(R.id.number_of_players_best) TextView numberOfPlayersBest;
	@BindView(R.id.number_of_players_recommended) TextView numberOfPlayersRecommended;
	@BindView(R.id.number_of_players_votes) TextView numberOfPlayersVotes;

	@BindView(R.id.play_time) TextView playTimeView;

	@BindView(R.id.player_age_message) TextView playerAgeMessage;
	@BindView(R.id.player_age_poll) TextView playerAgePoll;
	@BindView(R.id.player_age_votes) TextView playerAgeVotes;

	@BindView(R.id.game_rank) TextView rankView;
	@BindView(R.id.game_types) TextView typesView;
	@BindView(R.id.icon_favorite) ImageView favoriteView;

	@BindView(R.id.game_info_designers) GameDetailRow designersView;
	@BindView(R.id.game_info_artists) GameDetailRow artistsView;
	@BindView(R.id.game_info_publishers) GameDetailRow publishersView;
	@BindView(R.id.game_info_categories) GameDetailRow categoriesView;
	@BindView(R.id.game_info_mechanics) GameDetailRow mechanicsView;
	@BindView(R.id.game_info_expansions) GameDetailRow expansionsView;
	@BindView(R.id.game_info_base_games) GameDetailRow baseGamesView;

	@BindView(R.id.collection_card) View collectionCard;
	@BindView(R.id.collection_container) ViewGroup collectionContainer;

	@BindView(R.id.plays_card) View playsCard;
	@BindView(R.id.plays_root) View playsRoot;
	@BindView(R.id.plays_label) TextView playsLabel;
	@BindView(R.id.plays_last_play) TextView lastPlayView;
	@BindView(R.id.play_stats_root) View playStatsRoot;
	@BindView(R.id.colors_root) View colorsRoot;
	@BindView(R.id.game_colors_label) TextView colorsLabel;

	@BindView(R.id.game_ratings_votes) TextView ratingsVotes;

	@BindView(R.id.game_comments_label) TextView commentsLabel;

	@BindView(R.id.forums_last_post_date) TimestampView forumsLastPostDateView;

	@BindView(R.id.game_weight_message) TextView weightMessage;
	@BindView(R.id.game_weight_score) TextView weightScore;
	@BindView(R.id.game_weight_votes) TextView weightVotes;

	@BindView(R.id.language_dependence_message) TextView languageDependenceMessage;
	@BindView(R.id.language_dependence_score) TextView languageDependenceScore;
	@BindView(R.id.language_dependence_votes) TextView languageDependenceVotes;

	@BindView(R.id.users_count) TextView userCountView;

	@BindView(R.id.game_info_id) TextView idView;
	@BindView(R.id.game_info_last_updated) TimestampView updatedView;

	@BindViews({
		R.id.game_info_designers,
		R.id.game_info_artists,
		R.id.game_info_publishers,
		R.id.game_info_categories,
		R.id.game_info_mechanics,
		R.id.game_info_expansions,
		R.id.game_info_base_games
	}) List<GameDetailRow> colorizedRows;
	@BindViews({
		R.id.icon_favorite,
		R.id.icon_rating,
		R.id.icon_game_year_published,
		R.id.icon_play_time,
		R.id.icon_number_of_players,
		R.id.icon_player_age,
		R.id.icon_plays,
		R.id.icon_play_stats,
		R.id.icon_colors,
		R.id.icon_forums,
		R.id.icon_comments,
		R.id.icon_weight,
		R.id.icon_language_dependence,
		R.id.icon_users,
		R.id.icon_link_bgg,
		R.id.icon_link_bg_prices,
		R.id.icon_link_amazon,
		R.id.icon_link_ebay
	}) List<ImageView> colorizedIcons;
	@BindViews({
		R.id.collection_add_button
	}) List<Button> colorizedButtons;

	@ColorInt private int iconColor;
	private Palette palette;
	private Palette.Swatch darkSwatch;
	private ShowcaseViewWizard showcaseViewWizard;

	@Override
	@DebugLog
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Icepick.restoreInstanceState(this, savedInstanceState);
		setHasOptionsMenu(true);

		final Intent intent = UIUtils.fragmentArgumentsToIntent(getArguments());
		gameUri = intent.getData();
	}

	@DebugLog
	@Override
	public void onStart() {
		super.onStart();
		EventBus.getDefault().register(this);
	}

	@Override
	public void onStop() {
		super.onStop();
		EventBus.getDefault().unregister(this);
	}

	@Override
	@DebugLog
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View rootView = inflater.inflate(R.layout.fragment_game, container, false);
		unbinder = ButterKnife.bind(this, rootView);

		colorize();

		mightNeedRefreshing = true;
		LoaderManager lm = getLoaderManager();
		lm.restartLoader(GAME_TOKEN, null, this);
		lm.restartLoader(RANK_TOKEN, null, this);
		if (shouldShowPlays()) {
			lm.restartLoader(PlaysQuery._TOKEN, null, this);
		}
		if (PreferencesUtils.showLogPlay(getActivity())) {
			lm.restartLoader(ColorQuery._TOKEN, null, this);
		}
		lm.restartLoader(LanguagePollQuery._TOKEN, null, this);
		lm.restartLoader(AgePollQuery._TOKEN, null, this);
		lm.restartLoader(SuggestedPlayerCountQuery._TOKEN, null, this);

		showcaseViewWizard = setUpShowcaseViewWizard();
		showcaseViewWizard.maybeShowHelp();
		return rootView;
	}

	@Override
	@DebugLog
	public void onDestroyView() {
		super.onDestroyView();
		unbinder.unbind();
	}

	@Override
	@DebugLog
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		Icepick.saveInstanceState(this, outState);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.help, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.menu_help) {
			showcaseViewWizard.showHelp();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@NonNull
	private ShowcaseViewWizard setUpShowcaseViewWizard() {
		ShowcaseViewWizard wizard = new ShowcaseViewWizard(getActivity(), HelpUtils.HELP_GAME_KEY, HELP_VERSION);
		wizard.addTarget(R.string.help_game_menu, Target.NONE);
		wizard.addTarget(R.string.help_game_log_play, new SafeViewTarget(R.id.fab, getActivity()));
		wizard.addTarget(R.string.help_game_poll, new SafeViewTarget(R.id.number_of_players, getActivity()));
		wizard.addTarget(-1, new SafeViewTarget(R.id.player_age_root, getActivity()));
		return wizard;
	}

	@Override
	@DebugLog
	public Loader<Cursor> onCreateLoader(int id, Bundle data) {
		CursorLoader loader = null;
		int gameId = Games.getGameId(gameUri);
		switch (id) {
			case GAME_TOKEN:
				loader = new CursorLoader(getActivity(), gameUri, Game.PROJECTION, null, null, null);
				break;
			case DESIGNER_TOKEN:
				loader = new CursorLoader(getActivity(), GameDesigner.buildUri(gameId), GameDesigner.PROJECTION, null, null, null);
				break;
			case ARTIST_TOKEN:
				loader = new CursorLoader(getActivity(), GameArtist.buildUri(gameId), GameArtist.PROJECTION, null, null, null);
				break;
			case PUBLISHER_TOKEN:
				loader = new CursorLoader(getActivity(), GamePublisher.buildUri(gameId), GamePublisher.PROJECTION, null, null, null);
				break;
			case CATEGORY_TOKEN:
				loader = new CursorLoader(getActivity(), GameCategory.buildUri(gameId), GameCategory.PROJECTION, null, null, null);
				break;
			case MECHANIC_TOKEN:
				loader = new CursorLoader(getActivity(), GameMechanic.buildUri(gameId), GameMechanic.PROJECTION, null, null, null);
				break;
			case EXPANSION_TOKEN:
				loader = new CursorLoader(getActivity(), GameExpansion.buildUri(gameId), GameExpansion.PROJECTION, GameExpansion.getSelection(), GameExpansion.getSelectionArgs(), null);
				break;
			case BASE_GAME_TOKEN:
				loader = new CursorLoader(getActivity(), Games.buildExpansionsUri(gameId), GameBaseGame.PROJECTION, GameBaseGame.getSelection(), GameBaseGame.getSelectionArgs(), null);
				break;
			case RANK_TOKEN:
				loader = new CursorLoader(getActivity(), GameRank.buildUri(gameId), GameRank.PROJECTION, null, null, null);
				break;
			case CollectionQuery._TOKEN:
				loader = new CursorLoader(getActivity(), Collection.CONTENT_URI, CollectionQuery.PROJECTION, "collection." + Collection.GAME_ID + "=?", new String[] { String.valueOf(gameId) }, null);
				break;
			case PlaysQuery._TOKEN:
				// retrieve plays that aren't pending delete (optionally only completed plays)
				String selection = String.format("%s=? AND %s", Plays.OBJECT_ID, SelectionBuilder.whereZeroOrNull(Plays.DELETE_TIMESTAMP));
				if (!PreferencesUtils.logPlayStatsIncomplete(getActivity())) {
					selection += String.format(" AND %s!=1", Plays.INCOMPLETE);
				}
				loader = new CursorLoader(getActivity(),
					Plays.CONTENT_URI,
					PlaysQuery.PROJECTION,
					selection,
					new String[] { String.valueOf(gameId) },
					null);
				break;
			case ColorQuery._TOKEN:
				loader = new CursorLoader(getActivity(), GameColorAdapter.createUri(gameId), GameColorAdapter.PROJECTION, null, null, null);
				break;
			case LanguagePollQuery._TOKEN:
				loader = new CursorLoader(getActivity(),
					Games.buildPollResultsResultUri(gameId, PollFragment.LANGUAGE_DEPENDENCE),
					LanguagePollQuery.PROJECTION,
					null,
					null,
					LanguagePollQuery.SORT);
				break;
			case AgePollQuery._TOKEN:
				loader = new CursorLoader(getActivity(),
					Games.buildPollResultsResultUri(gameId, PollFragment.SUGGESTED_PLAYER_AGE),
					AgePollQuery.PROJECTION,
					null,
					null,
					AgePollQuery.SORT);
				break;
			case SuggestedPlayerCountQuery._TOKEN:
				loader = new CursorLoader(getActivity(),
					Games.buildSuggestedPlayerCountPollResultsUri(gameId),
					SuggestedPlayerCountQuery.PROJECTION,
					null, null, null);
				break;
			default:
				Timber.w("Invalid query token=%s", id);
				break;
		}
		return loader;
	}

	@Override
	@DebugLog
	public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
		if (getActivity() == null) return;

		switch (loader.getId()) {
			case GAME_TOKEN:
				onGameQueryComplete(cursor);
				LoaderManager lm = getLoaderManager();
				if (shouldShowCollection()) lm.restartLoader(CollectionQuery._TOKEN, null, this);
				lm.restartLoader(DESIGNER_TOKEN, null, this);
				lm.restartLoader(ARTIST_TOKEN, null, this);
				lm.restartLoader(PUBLISHER_TOKEN, null, this);
				lm.restartLoader(CATEGORY_TOKEN, null, this);
				lm.restartLoader(MECHANIC_TOKEN, null, this);
				lm.restartLoader(EXPANSION_TOKEN, null, this);
				lm.restartLoader(BASE_GAME_TOKEN, null, this);
				fetchForumInfo();
				break;
			case DESIGNER_TOKEN:
				onListQueryComplete(cursor, designersView);
				break;
			case ARTIST_TOKEN:
				onListQueryComplete(cursor, artistsView);
				break;
			case PUBLISHER_TOKEN:
				onListQueryComplete(cursor, publishersView);
				break;
			case CATEGORY_TOKEN:
				onListQueryComplete(cursor, categoriesView);
				break;
			case MECHANIC_TOKEN:
				onListQueryComplete(cursor, mechanicsView);
				break;
			case EXPANSION_TOKEN:
				onListQueryComplete(cursor, expansionsView);
				break;
			case BASE_GAME_TOKEN:
				onListQueryComplete(cursor, baseGamesView);
				break;
			case RANK_TOKEN:
				onRankQueryComplete(cursor);
				break;
			case CollectionQuery._TOKEN:
				onCollectionQueryComplete(cursor);
				break;
			case PlaysQuery._TOKEN:
				onPlaysQueryComplete(cursor);
				break;
			case ColorQuery._TOKEN:
				playsCard.setVisibility(VISIBLE);
				colorsRoot.setVisibility(VISIBLE);
				int count = cursor == null ? 0 : cursor.getCount();
				colorsLabel.setText(PresentationUtils.getQuantityText(getActivity(), R.plurals.colors_suffix, count, count));
				break;
			case LanguagePollQuery._TOKEN:
				onLanguagePollQueryComplete(cursor);
				break;
			case AgePollQuery._TOKEN:
				onAgePollQueryComplete(cursor);
				break;
			case SuggestedPlayerCountQuery._TOKEN:
				onPlayerCountQueryComplete(cursor);
				break;
			default:
				cursor.close();
				break;
		}
	}

	private void fetchForumInfo() {
		if (forumsLastPostDateView.getVisibility() == VISIBLE) return;

		BggService bggService = Adapter.createForXml();
		Call<ForumListResponse> call = bggService.forumList(BggService.FORUM_TYPE_THING, Games.getGameId(gameUri));
		call.enqueue(new Callback<ForumListResponse>() {
			@Override
			public void onResponse(Call<ForumListResponse> call, Response<ForumListResponse> response) {
				if (response.isSuccessful() && forumsLastPostDateView != null) {
					long lastPostDate = 0;
					String title = "";
					for (Forum forum : response.body().getForums()) {
						if (forum.lastPostDate() > lastPostDate) {
							lastPostDate = forum.lastPostDate();
							title = forum.title;
						}
					}
					forumsLastPostDateView.setFormatArg(title);
					forumsLastPostDateView.setTimestamp(lastPostDate);
				}
			}

			@Override
			public void onFailure(Call<ForumListResponse> call, Throwable t) {
				Timber.w("Failed fetching forum for game %s: %s", Games.getGameId(gameUri), t.getMessage());
			}
		});
	}

	@Override
	@DebugLog
	public void onLoaderReset(Loader<Cursor> loader) {
	}

	@DebugLog
	public void onPaletteGenerated(Palette palette) {
		this.palette = palette;
		colorize();
	}

	@DebugLog
	private void colorize() {
		if (palette == null || colorizedRows == null || !isAdded()) return;

		Palette.Swatch swatch = PaletteUtils.getIconSwatch(palette);
		iconColor = swatch.getRgb();
		ButterKnife.apply(colorizedRows, GameDetailRow.colorIconSetter, swatch);
		ButterKnife.apply(colorizedIcons, PaletteUtils.colorIconSetter, swatch);
		ButterKnife.apply(colorizedButtons, PaletteUtils.colorButtonSetter, swatch);
		darkSwatch = PaletteUtils.getDarkSwatch(palette);

		ScrimUtils.applyWhiteScrim(descriptionView);
	}

	@DebugLog
	private void onGameQueryComplete(Cursor cursor) {
		if (cursor == null || !cursor.moveToFirst()) {
			if (mightNeedRefreshing) {
				triggerRefresh();
			}
			return;
		}

		Game game = Game.fromCursor(cursor);

		notifyChange(game);
		gameName = game.Name;
		imageUrl = game.ImageUrl;
		thumbnailUrl = game.ThumbnailUrl;
		arePlayersCustomSorted = game.CustomPlayerSort;

		yearPublishedView.setText(PresentationUtils.describeYear(getContext(), game.YearPublished));

		rankView.setText(PresentationUtils.describeRank(getContext(), game.Rank, BggService.RANK_TYPE_SUBTYPE, game.Subtype));
		favoriteView.setImageResource(game.IsFavorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
		favoriteView.setTag(R.id.favorite, game.IsFavorite);

		ratingView.setText(PresentationUtils.describeRating(getContext(), game.Rating));
		ratingsVotes.setText(PresentationUtils.getQuantityText(getActivity(), R.plurals.votes_suffix, game.UsersRated, game.UsersRated));
		ColorUtils.setTextViewBackground(ratingView, ColorUtils.getRatingColor(game.Rating));

		idView.setText(String.valueOf(game.Id));
		updatedView.setTimestamp(game.Updated);
		UIUtils.setTextMaybeHtml(descriptionView, game.Description);
		numberOfPlayersView.setText(PresentationUtils.describePlayerRange(getContext(), game.MinPlayers, game.MaxPlayers));

		playTimeView.setText(PresentationUtils.describeMinuteRange(getContext(), game.MinPlayingTime, game.MaxPlayingTime, game.PlayingTime));

		playerAgeMessage.setText(PresentationUtils.describePlayerAge(getContext(), game.MinimumAge));

		commentsLabel.setText(PresentationUtils.getQuantityText(getActivity(), R.plurals.comments_suffix, game.UsersCommented, game.UsersCommented));

		weightMessage.setText(PresentationUtils.describeWeight(getActivity(), game.AverageWeight));
		if (game.AverageWeight >= 1 && game.AverageWeight <= 5) {
			weightScore.setText(PresentationUtils.describeScore(getContext(), game.AverageWeight));
			ColorUtils.setTextViewBackground(weightScore, ColorUtils.getFiveStageColor(game.AverageWeight));
			weightScore.setVisibility(VISIBLE);
		} else {
			weightScore.setVisibility(GONE);
		}
		weightVotes.setText(PresentationUtils.getQuantityText(getActivity(), R.plurals.votes_suffix, game.NumberWeights, game.NumberWeights));

		final int maxUsers = game.getMaxUsers();
		userCountView.setText(PresentationUtils.getQuantityText(getActivity(), R.plurals.users_suffix, maxUsers, maxUsers));

		if (shouldShowPlays()) {
			playsCard.setVisibility(VISIBLE);
			playStatsRoot.setVisibility(VISIBLE);
		}

		if (mightNeedRefreshing &&
			(game.PollsCount == 0 ||
				game.SuggestedPlayerCountPollVoteTotal == 0 ||
				DateTimeUtils.howManyDaysOld(game.Updated) > AGE_IN_DAYS_TO_REFRESH)) {
			triggerRefresh();
		}
		mightNeedRefreshing = false;
	}

	@DebugLog
	private boolean shouldShowCollection() {
		String[] syncStatuses = PreferencesUtils.getSyncStatuses(getContext());
		return Authenticator.isSignedIn(getActivity()) && syncStatuses != null && syncStatuses.length > 0;
	}

	@DebugLog
	private boolean shouldShowPlays() {
		return Authenticator.isSignedIn(getActivity()) && PreferencesUtils.getSyncPlays(getContext());
	}

	@DebugLog
	private void notifyChange(Game game) {
		GameInfoChangedEvent event = new GameInfoChangedEvent(game.Name, game.Subtype, game.ImageUrl, game.ThumbnailUrl, game.CustomPlayerSort, game.IsFavorite);
		EventBus.getDefault().post(event);
	}

	@DebugLog
	private void onListQueryComplete(Cursor cursor, GameDetailRow view) {
		if (cursor == null || !cursor.moveToFirst()) {
			view.setVisibility(GONE);
			view.clear();
		} else {
			view.setVisibility(VISIBLE);
			view.bind(cursor, GameList.NAME_COLUMN_INDEX, GameList.ID_COLUMN_INDEX, Games.getGameId(gameUri), gameName);
		}
	}

	@DebugLog
	private void onRankQueryComplete(Cursor cursor) {
		if (typesView != null) {
			if (cursor != null && cursor.getCount() > 0) {
				CharSequence cs = null;
				while (cursor.moveToNext()) {
					GameRank rank = GameRank.fromCursor(cursor);
					if (rank.isFamilyType()) {
						if (cs != null) {
							cs = PresentationUtils.getText(getContext(), R.string.rank_div, cs,
								PresentationUtils.describeRank(getContext(), rank.getRank(), rank.getType(), rank.getName()));
						} else {
							cs = PresentationUtils.describeRank(getContext(), rank.getRank(), rank.getType(), rank.getName());
						}
					}
				}
				if (TextUtils.isEmpty(cs)) {
					typesView.setVisibility(GONE);
				} else {
					typesView.setText(cs);
					typesView.setVisibility(VISIBLE);
				}
			} else {
				typesView.setVisibility(GONE);
			}
		}
	}

	@DebugLog
	private void onCollectionQueryComplete(Cursor cursor) {
		if (cursor != null && cursor.moveToFirst()) {
			collectionCard.setVisibility(VISIBLE);
			collectionContainer.removeAllViews();
			do {
				GameCollectionRow row = new GameCollectionRow(getActivity());

				final long internalId = cursor.getLong(CollectionQuery._ID);
				final int gameId = Games.getGameId(gameUri);
				final int collectionId = cursor.getInt(CollectionQuery.COLLECTION_ID);
				final int yearPublished = cursor.getInt(CollectionQuery.YEAR_PUBLISHED);
				final String imageUrl = cursor.getString(CollectionQuery.COLLECTION_IMAGE_URL);
				row.bind(internalId, gameId, gameName, collectionId, yearPublished, imageUrl);

				final String thumbnailUrl = cursor.getString(CollectionQuery.COLLECTION_THUMBNAIL_URL);
				final String collectionName = cursor.getString(CollectionQuery.COLLECTION_NAME);
				final int collectionYearPublished = cursor.getInt(CollectionQuery.COLLECTION_YEAR);
				final int numberOfPlays = cursor.getInt(CollectionQuery.NUM_PLAYS);
				final String comment = cursor.getString(CollectionQuery.COMMENT);
				final double rating = cursor.getDouble(CollectionQuery.RATING);
				List<String> status = new ArrayList<>();
				for (int i = CollectionQuery.STATUS_1; i <= CollectionQuery.STATUS_N; i++) {
					if (cursor.getInt(i) == 1) {
						if (i == CollectionQuery.STATUS_WISHLIST) {
							status.add(PresentationUtils.describeWishlist(getActivity(),
								cursor.getInt(CollectionQuery.STATUS_WISHLIST_PRIORITY)));
						} else {
							int index = i - CollectionQuery.STATUS_1;
							status.add(getResources().getStringArray(R.array.collection_status_filter_entries)[index]);
						}
					}
				}

				row.setThumbnail(thumbnailUrl);
				row.setStatus(status, numberOfPlays, rating, comment);
				row.setDescription(collectionName, collectionYearPublished);
				row.setComment(comment);
				row.setRating(rating);

				collectionContainer.addView(row);
			} while (cursor.moveToNext());
		} else {
			collectionCard.setVisibility(GONE);
		}
	}

	@OnClick(R.id.collection_add_button)
	void onAddToCollectionClick() {
		CollectionStatusDialogFragment statusDialogFragment = CollectionStatusDialogFragment.newInstance(
			collectionContainer,
			new CollectionStatusDialogListener() {
				@Override
				public void onSelectStatuses(List<String> selectedStatuses, int wishlistPriority) {
					int gameId = Games.getGameId(gameUri);
					AddCollectionItemTask task = new AddCollectionItemTask(getActivity(), gameId, selectedStatuses, wishlistPriority);
					TaskUtils.executeAsyncTask(task);
				}
			}
		);
		statusDialogFragment.setTitle(R.string.title_add_a_copy);
		DialogUtils.showFragment(getActivity(), statusDialogFragment, "status_dialog");
	}

	@DebugLog
	private void onPlaysQueryComplete(Cursor cursor) {
		if (cursor.moveToFirst()) {
			playsCard.setVisibility(VISIBLE);
			playsRoot.setVisibility(VISIBLE);

			int sum = cursor.getInt(PlaysQuery.SUM_QUANTITY);
			long date = CursorUtils.getDateInMillis(cursor, PlaysQuery.MAX_DATE);

			String description = PresentationUtils.describePlayCount(getActivity(), sum);
			if (!TextUtils.isEmpty(description)) {
				description = " (" + description + ")";
			}
			playsLabel.setText(PresentationUtils.getQuantityText(getActivity(), R.plurals.plays_prefix, sum, sum, description));

			if (date > 0) {
				lastPlayView.setText(PresentationUtils.getText(getActivity(), R.string.last_played_prefix, PresentationUtils.describePastDaySpan(date)));
				lastPlayView.setVisibility(VISIBLE);
			} else {
				lastPlayView.setVisibility(GONE);
			}
		}
	}

	@DebugLog
	private void onLanguagePollQueryComplete(Cursor cursor) {
		int totalLevel = 0;
		int totalVotes = 0;
		if (cursor != null) {
			while (cursor.moveToNext()) {
				totalVotes = cursor.getInt(LanguagePollQuery.POLL_TOTAL_VOTES);
				int level = (cursor.getInt(LanguagePollQuery.POLL_RESULTS_RESULT_LEVEL) - 1) % 5 + 1;
				int votes = cursor.getInt(LanguagePollQuery.POLL_RESULTS_RESULT_VOTES);
				totalLevel += votes * level;
			}
		}
		double score = (double) totalLevel / totalVotes;
		languageDependenceMessage.setText(PresentationUtils.describeLanguageDependence(getActivity(), score));
		if (score >= 1 && score <= 5) {
			languageDependenceScore.setText(PresentationUtils.describeScore(getContext(), score));
			ColorUtils.setTextViewBackground(languageDependenceScore, ColorUtils.getFiveStageColor(score));
			languageDependenceScore.setVisibility(VISIBLE);
		} else {
			languageDependenceScore.setVisibility(GONE);
		}
		PresentationUtils.setTextOrHide(languageDependenceVotes,
			PresentationUtils.getQuantityText(getActivity(), R.plurals.votes_suffix, totalVotes, totalVotes));
	}

	@DebugLog
	private void onAgePollQueryComplete(Cursor cursor) {
		String currentValue = "";
		int maxVotes = 0;
		int totalVotes = 0;
		if (cursor != null && cursor.moveToFirst()) {
			totalVotes = cursor.getInt(AgePollQuery.POLL_TOTAL_VOTES);
			do {
				String value = cursor.getString(AgePollQuery.POLL_RESULTS_RESULT_VALUE);
				int votes = cursor.getInt(AgePollQuery.POLL_RESULTS_RESULT_VOTES);

				if (votes > maxVotes) currentValue = value;
			} while (cursor.moveToNext());
		}

		if (!TextUtils.isEmpty(currentValue))
			PresentationUtils.setTextOrHide(playerAgePoll, PresentationUtils.describePlayerAge(getContext(), currentValue));

		PresentationUtils.setTextOrHide(playerAgeVotes,
			PresentationUtils.getQuantityText(getActivity(), R.plurals.votes_suffix, totalVotes, totalVotes));
	}

	@DebugLog
	private void onPlayerCountQueryComplete(Cursor cursor) {
		int totalVotes = 0;
		if (cursor != null && cursor.moveToFirst()) {
			totalVotes = cursor.getInt(SuggestedPlayerCountQuery.TOTAL_VOTE_COUNT);

			List<Integer> bestCounts = new ArrayList<>();
			List<Integer> recommendedCounts = new ArrayList<>();
			do {
				int playerCount = cursor.getInt(SuggestedPlayerCountQuery.PLAYER_COUNT);
				int recommendation = cursor.getInt(SuggestedPlayerCountQuery.RECOMMENDATION);
				if (recommendation == PlayerCountRecommendation.BEST) {
					bestCounts.add(playerCount);
					recommendedCounts.add(playerCount);
				} else if (recommendation == PlayerCountRecommendation.RECOMMENDED) {
					recommendedCounts.add(playerCount);
				}
			} while (cursor.moveToNext());

			if (bestCounts.size() > 0) {
				PresentationUtils.setTextOrHide(numberOfPlayersBest,
					PresentationUtils.getText(getContext(), R.string.best_prefix, StringUtils.formatRange(bestCounts)));
			}
			if (recommendedCounts.size() > 0 && !bestCounts.equals(recommendedCounts)) {
				PresentationUtils.setTextOrHide(numberOfPlayersRecommended,
					PresentationUtils.getText(getContext(), R.string.recommended_prefix, StringUtils.formatRange(recommendedCounts)));
			}
		} else {
			numberOfPlayersBest.setVisibility(GONE);
			numberOfPlayersRecommended.setVisibility(GONE);
		}
		PresentationUtils.setTextOrHide(numberOfPlayersVotes,
			PresentationUtils.getQuantityText(getContext(), R.plurals.votes_suffix, totalVotes, totalVotes));
	}

	@OnClick(R.id.icon_favorite)
	@DebugLog
	public void onFavoriteClick() {
		boolean isFavorite = (boolean) favoriteView.getTag(R.id.favorite);
		TaskUtils.executeAsyncTask(new FavoriteGameTask(getContext(), Games.getGameId(gameUri), !isFavorite));
	}

	@OnClick(R.id.game_rank_root)
	@DebugLog
	public void onRankClick() {
		Bundle arguments = new Bundle(2);
		arguments.putInt(ActivityUtils.KEY_GAME_ID, Games.getGameId(gameUri));
		DialogUtils.launchDialog(this, new RanksFragment(), "ranks-dialog", arguments);
	}

	@SuppressLint("InflateParams")
	@OnClick(R.id.game_description)
	@DebugLog
	public void onDescriptionClick() {
		View v = LayoutInflater.from(getContext()).inflate(R.layout.dialog_text, null);
		((TextView) v.findViewById(R.id.text)).setText(descriptionView.getText());
		new Builder(getContext(), R.style.Theme_bgglight_Dialog_Alert)
			.setView(v)
			.show();
	}

	@OnClick(R.id.plays_root)
	@DebugLog
	public void onPlaysClick() {
		Intent intent = ActivityUtils.createGamePlaysIntent(getActivity(),
			gameUri,
			gameName,
			imageUrl,
			thumbnailUrl,
			arePlayersCustomSorted,
			iconColor);
		startActivity(intent);
	}

	@OnClick(R.id.play_stats_root)
	@DebugLog
	public void onPlayStatsClick() {
		Intent intent = new Intent(getActivity(), GamePlayStatsActivity.class);
		intent.setData(gameUri);
		intent.putExtra(ActivityUtils.KEY_GAME_NAME, gameName);
		if (palette != null) {
			final Swatch swatch = PaletteUtils.getHeaderSwatch(palette);
			intent.putExtra(ActivityUtils.KEY_HEADER_COLOR, swatch.getRgb());
		}
		startActivity(intent);
	}

	@OnClick(R.id.colors_root)
	@DebugLog
	public void onColorsClick() {
		Intent intent = new Intent(getActivity(), GameColorsActivity.class);
		intent.setData(gameUri);
		intent.putExtra(ActivityUtils.KEY_GAME_NAME, gameName);
		intent.putExtra(ActivityUtils.KEY_ICON_COLOR, iconColor);
		startActivity(intent);
	}

	@OnClick(R.id.forums_root)
	@DebugLog
	public void onForumsClick() {
		Intent intent = new Intent(getActivity(), GameForumsActivity.class);
		intent.setData(gameUri);
		intent.putExtra(ActivityUtils.KEY_GAME_NAME, gameName);
		startActivity(intent);
	}

	@OnClick(R.id.language_dependence_root)
	@DebugLog
	public void onLanguageDependenceClick() {
		Bundle arguments = new Bundle(2);
		arguments.putInt(ActivityUtils.KEY_GAME_ID, Games.getGameId(gameUri));
		arguments.putString(ActivityUtils.KEY_TYPE, PollFragment.LANGUAGE_DEPENDENCE);
		DialogUtils.launchDialog(this, new PollFragment(), "poll-dialog", arguments);
	}

	@OnClick(R.id.comments_root)
	@DebugLog
	public void onCommentsClick() {
		Intent intent = new Intent(getActivity(), CommentsActivity.class);
		intent.setData(gameUri);
		intent.putExtra(ActivityUtils.KEY_GAME_NAME, gameName);
		startActivity(intent);
	}

	@OnClick(R.id.users_count_root)
	@DebugLog
	public void onUsersClick() {
		Bundle arguments = new Bundle(1);
		arguments.putInt(ActivityUtils.KEY_GAME_ID, Games.getGameId(gameUri));
		arguments.putInt(ActivityUtils.KEY_ICON_COLOR, darkSwatch.getRgb());
		DialogUtils.launchDialog(this, new GameUsersDialogFragment(), "users-dialog", arguments);
	}

	@DebugLog
	@OnClick(R.id.ratings_root)
	public void onRatingsClick() {
		Intent intent = new Intent(getActivity(), CommentsActivity.class);
		intent.setData(gameUri);
		intent.putExtra(ActivityUtils.KEY_GAME_NAME, gameName);
		intent.putExtra(ActivityUtils.KEY_SORT, CommentsActivity.SORT_RATING);
		startActivity(intent);
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onEvent(CollectionItemUpdatedEvent event) {
		SyncService.sync(getActivity(), SyncService.FLAG_SYNC_COLLECTION_UPLOAD);
	}

	@SuppressWarnings("unused")
	@DebugLog
	@OnClick({ R.id.link_bgg, R.id.link_bg_prices, R.id.link_amazon, R.id.link_amazon_uk, R.id.link_amazon_de, R.id.link_ebay })
	void onLinkClick(View view) {
		switch (view.getId()) {
			case R.id.link_bgg:
				ActivityUtils.linkBgg(getActivity(), Games.getGameId(gameUri));
				break;
			case R.id.link_bg_prices:
				ActivityUtils.linkBgPrices(getActivity(), gameName);
				break;
			case R.id.link_amazon:
				ActivityUtils.linkAmazon(getActivity(), gameName, ActivityUtils.LINK_AMAZON_COM);
				break;
			case R.id.link_amazon_uk:
				ActivityUtils.linkAmazon(getActivity(), gameName, ActivityUtils.LINK_AMAZON_UK);
				break;
			case R.id.link_amazon_de:
				ActivityUtils.linkAmazon(getActivity(), gameName, ActivityUtils.LINK_AMAZON_DE);
				break;
			case R.id.link_ebay:
				ActivityUtils.linkEbay(getActivity(), gameName);
				break;
		}
	}

	@OnClick({ R.id.player_age_root })
	@DebugLog
	public void onPollClick() {
		Bundle arguments = new Bundle(2);
		arguments.putInt(ActivityUtils.KEY_GAME_ID, Games.getGameId(gameUri));
		arguments.putString(ActivityUtils.KEY_TYPE, PollFragment.SUGGESTED_PLAYER_AGE);
		DialogUtils.launchDialog(this, new PollFragment(), "poll-dialog", arguments);
	}

	@OnClick({ R.id.number_of_players_root })
	@DebugLog
	public void onSuggestedPlayerCountPollClick() {
		Bundle arguments = new Bundle(2);
		arguments.putInt(ActivityUtils.KEY_GAME_ID, Games.getGameId(gameUri));
		DialogUtils.launchDialog(this, new SuggestedPlayerCountPollFragment(), "suggested-player-count-poll-dialog", arguments);
	}


	@DebugLog
	public boolean triggerRefresh() {
		mightNeedRefreshing = false;
		if (isRefreshing == SYNC_NONE) {
			isRefreshing = SYNC_GAME | SYNC_PLAYS | SYNC_COLLECTION;
			int gameId = Games.getGameId(gameUri);
			TaskUtils.executeAsyncTask(new SyncGameTask(getContext(), gameId));
			TaskUtils.executeAsyncTask(new SyncCollectionByGameTask(getContext(), gameId));
			TaskUtils.executeAsyncTask(new SyncPlaysByGameTask(getContext(), gameId));
			return true;
		}
		return false;
	}

	@SuppressWarnings("unused")
	@DebugLog
	@Subscribe(threadMode = ThreadMode.MAIN)
	public void onEvent(SyncGameTask.CompletedEvent event) {
		if (event.getGameId() == Games.getGameId(gameUri)) {
			finishSync(SYNC_GAME);
		}
	}

	@SuppressWarnings("unused")
	@DebugLog
	@Subscribe(threadMode = ThreadMode.MAIN)
	public void onEvent(SyncPlaysByGameTask.CompletedEvent event) {
		if (event.getGameId() == Games.getGameId(gameUri)) {
			finishSync(SYNC_PLAYS);
		}
	}

	@SuppressWarnings("unused")
	@DebugLog
	@Subscribe(threadMode = ThreadMode.MAIN)
	public void onEvent(SyncCollectionByGameTask.CompletedEvent event) {
		if (event.getGameId() == Games.getGameId(gameUri)) {
			finishSync(SYNC_COLLECTION);
		}
	}

	private void finishSync(int syncType) {
		isRefreshing &= ~syncType;
		if (isRefreshing == SYNC_NONE) {
			EventBus.getDefault().post(new SyncCompleteEvent(Games.getGameId(gameUri)));
		}
	}

	public static class SyncCompleteEvent {
		private final int gameId;

		public SyncCompleteEvent(int gameId) {
			this.gameId = gameId;
		}

		public int getGameId() {
			return gameId;
		}
	}

	private interface CollectionQuery {
		String[] PROJECTION = { Collection._ID, Collection.COLLECTION_ID, Collection.COLLECTION_NAME,
			Collection.COLLECTION_YEAR_PUBLISHED, Collection.COLLECTION_THUMBNAIL_URL, Collection.STATUS_OWN,
			Collection.STATUS_PREVIOUSLY_OWNED, Collection.STATUS_FOR_TRADE, Collection.STATUS_WANT,
			Collection.STATUS_WANT_TO_BUY, Collection.STATUS_WISHLIST, Collection.STATUS_WANT_TO_PLAY,
			Collection.STATUS_PREORDERED, Collection.STATUS_WISHLIST_PRIORITY, Collection.NUM_PLAYS,
			Collection.COMMENT, Games.YEAR_PUBLISHED, Collection.RATING, Collection.IMAGE_URL };
		int _TOKEN = 0x20;
		int _ID = 0;
		int COLLECTION_ID = 1;
		int COLLECTION_NAME = 2;
		int COLLECTION_YEAR = 3;
		int COLLECTION_THUMBNAIL_URL = 4;
		int STATUS_1 = 5;
		int STATUS_N = 12;
		int STATUS_WISHLIST = 10;
		int STATUS_WISHLIST_PRIORITY = 13;
		int NUM_PLAYS = 14;
		int COMMENT = 15;
		int YEAR_PUBLISHED = 16;
		int RATING = 17;
		int COLLECTION_IMAGE_URL = 18;
	}

	private interface PlaysQuery {
		String[] PROJECTION = { Plays._ID, Plays.MAX_DATE, Plays.SUM_QUANTITY, Plays.MAX_DATE };
		int _TOKEN = 0x21;
		int MAX_DATE = 1;
		int SUM_QUANTITY = 2;
	}

	private interface ColorQuery {
		int _TOKEN = 0x22;
	}

	private interface LanguagePollQuery {
		String[] PROJECTION = {
			GamePollResultsResult.POLL_RESULTS_RESULT_VOTES,
			GamePollResultsResult.POLL_RESULTS_RESULT_LEVEL,
			GamePolls.POLL_TOTAL_VOTES
		};
		int _TOKEN = 0x23;
		int POLL_RESULTS_RESULT_VOTES = 0;
		int POLL_RESULTS_RESULT_LEVEL = 1;
		int POLL_TOTAL_VOTES = 2;
		String SORT = GamePollResultsResult.POLL_RESULTS_SORT_INDEX + " ASC, " + GamePollResultsResult.POLL_RESULTS_RESULT_SORT_INDEX;
	}

	private interface AgePollQuery {
		String[] PROJECTION = {
			GamePollResultsResult.POLL_RESULTS_RESULT_VOTES,
			GamePollResultsResult.POLL_RESULTS_RESULT_VALUE,
			GamePolls.POLL_TOTAL_VOTES
		};
		int _TOKEN = 0x24;
		int POLL_RESULTS_RESULT_VOTES = 0;
		int POLL_RESULTS_RESULT_VALUE = 1;
		int POLL_TOTAL_VOTES = 2;
		String SORT = GamePollResultsResult.POLL_RESULTS_SORT_INDEX + " ASC, " + GamePollResultsResult.POLL_RESULTS_RESULT_SORT_INDEX;
	}

	private interface SuggestedPlayerCountQuery {
		int _TOKEN = 0x25;
		String[] PROJECTION = {
			GameSuggestedPlayerCountPollPollResults.SUGGESTED_PLAYER_COUNT_POLL_VOTE_TOTAL,
			GameSuggestedPlayerCountPollPollResults.PLAYER_COUNT,
			GameSuggestedPlayerCountPollPollResults.RECOMMENDATION,
		};
		int TOTAL_VOTE_COUNT = 0;
		int PLAYER_COUNT = 1;
		int RECOMMENDATION = 2;
	}
}