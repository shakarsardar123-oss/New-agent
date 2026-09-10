/// StateNotifier that drives the reaction evaluation lifecycle.
///
/// Accepts a [ReactionContext], delegates to the [StyleAwareSelector]
/// (Step 4 intelligence), manages the [ReactionHistory], and exposes
/// the current reaction via a [ReactionState] stream.
///
/// Follows the [FloatingAuraStateNotifier] pattern: StateNotifier
/// with Result return types, proper error handling, and lifecycle
/// management.
///
/// Step 5: The engine now stays in 'reacting' status after selection
/// until the banner UI signals completion via [completeReaction].
/// The [ReactionLifecycleCoordinator] manages the banner animation
/// lifecycle and exposes a stream for the speech coordinator.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../errors/result.dart';
import 'reaction_model.dart';
import 'reaction_context.dart';
import 'reaction_state.dart';
import 'reaction_catalog.dart';
import 'reaction_history.dart';
import 'reaction_style_selector.dart';
import 'reaction_random.dart';
import 'reaction_clock.dart';
import 'reaction_failure.dart';
import 'reaction_lifecycle.dart';

class ReactionEngine extends StateNotifier<ReactionState> {
  ReactionEngine({
    required ReactionCatalog catalog,
    required StyleAwareSelector selector,
    required ReactionHistory history,
    required RandomSource randomSource,
    required Clock clock,
    ReactionLifecycleCoordinator? lifecycleCoordinator,
  })  : _catalog = catalog,
        _selector = selector,
        _history = history,
        _randomSource = randomSource,
        _clock = clock,
        _lifecycleCoordinator =
            lifecycleCoordinator ?? ReactionLifecycleCoordinator(),
        super(ReactionState.initial);

  final ReactionCatalog _catalog;
  final StyleAwareSelector _selector;
  final ReactionHistory _history;
  final RandomSource _randomSource;
  final Clock _clock;
  final ReactionLifecycleCoordinator _lifecycleCoordinator;

  /// The catalog instance (read-only access for testing).
  ReactionCatalog get catalog => _catalog;

  /// The history instance (read-only access for testing).
  ReactionHistory get history => _history;

  /// The selector instance (read-only access for testing).
  StyleAwareSelector get selector => _selector;

  /// The lifecycle coordinator (read-only access for testing and providers).
  ReactionLifecycleCoordinator get lifecycleCoordinator =>
      _lifecycleCoordinator;

  /// Evaluates whether a reaction should fire given [context].
  ///
  /// Returns [Result.success] with the selected [Reaction] if one
  /// was chosen, or [Result.failure] with [ReactionFailure] if the
  /// pipeline produced no result or encountered an error.
  ///
  /// Side effects:
  /// - Updates [ReactionState] via StateNotifier
  /// - Records the selected reaction in [ReactionHistory]
  /// - Starts the banner lifecycle coordinator
  ///
  /// Step 5: The engine now stays in 'reacting' status after
  /// selection. Call [completeReaction] when the banner UI finishes.
  Result<Reaction, ReactionFailure> evaluate(ReactionContext context) {
    try {
      state = state.copyWith(status: ReactionEngineStatus.evaluating);

      final selection = _selector.select(
        catalog: _catalog,
        context: context,
        history: _history,
        random: _randomSource,
      );

      if (!selection.hasReaction) {
        state = state.copyWith(
          status: ReactionEngineStatus.idle,
        );
        return Result.failure(
          ReactionFailure(
            message: 'No eligible reaction for trigger: ${context.trigger}',
            code: 'NO_ELIGIBLE_REACTION',
            phase: ReactionPhase.selection,
          ),
        );
      }

      final reaction = selection.reaction!;

      // Record in history (anti-repetition for next evaluation).
      _history.record(reaction.id);

      // Start the banner lifecycle.
      _lifecycleCoordinator.start(reaction);

      // Stay in 'reacting' — do NOT reset to idle here.
      // The banner UI will call completeReaction() when done.
      state = state.copyWith(
        status: ReactionEngineStatus.reacting,
        currentReaction: reaction,
        lastReactionId: reaction.id,
        lastSelectedAt: _clock.now(),
        totalSelections: state.totalSelections + 1,
        bannerLifecycle: ReactionBannerLifecycle.entering,
      );

      return Result.success(reaction);
    } catch (e) {
      state = state.copyWith(
        status: ReactionEngineStatus.error,
        lastError: e.toString(),
      );
      return Result.failure(
        ReactionFailure(
          message: 'Reaction evaluation failed: $e',
          code: 'EVALUATION_ERROR',
          phase: ReactionPhase.engineState,
        ),
      );
    }
  }

  /// Called by the banner UI when it finishes its animation.
  ///
  /// Transitions the engine from 'reacting' to 'idle' and
  /// completes the lifecycle coordinator.
  void completeReaction() {
    _lifecycleCoordinator.complete();
    state = state.copyWith(
      status: ReactionEngineStatus.idle,
      clearCurrentReaction: true,
      bannerLifecycle: ReactionBannerLifecycle.completed,
    );
    // Reset the lifecycle coordinator after completion.
    _lifecycleCoordinator.reset();
  }

  /// Updates the banner lifecycle phase from the UI.
  ///
  /// Called by the banner widget as it transitions through phases.
  void updateBannerLifecycle(ReactionBannerLifecycle phase) {
    state = state.copyWith(bannerLifecycle: phase);
  }

  /// Forces a reaction to be cleared (e.g. user dismissed the overlay).
  void clearReaction() {
    _lifecycleCoordinator.complete();
    state = state.copyWith(
      status: ReactionEngineStatus.idle,
      clearCurrentReaction: true,
      bannerLifecycle: ReactionBannerLifecycle.completed,
    );
    _lifecycleCoordinator.reset();
  }

  /// Resets the engine to initial state and clears history.
  void reset() {
    _history.clear();
    _lifecycleCoordinator.complete();
    _lifecycleCoordinator.reset();
    state = ReactionState.initial;
  }

  @override
  void dispose() {
    _history.clear();
    _lifecycleCoordinator.dispose();
    super.dispose();
  }
}
