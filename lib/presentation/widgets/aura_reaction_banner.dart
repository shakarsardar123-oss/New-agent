/// AURA Reaction Banner — iPhone-style animated notification banner.
///
/// Premium top-of-screen notification with dark/black background,
/// rounded corners, smooth slide-down/fade animations.
/// Supports all Step 4 visual styles: emoji, pixelArt, asciiArt,
/// code, meme, customVisual.
///
/// RTL-aware for Kurdish Sorani. When banner is visible,
/// assistant speech is held until the banner disappears
/// (managed by ReactionSpeechCoordinator).
///
/// This is a **Flutter overlay** within the app screens only.
/// FloatingAura (native Android View) is NOT modified.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/reaction/reaction.dart';
import '../../core/theme/app_colors.dart';
import '../../core/theme/app_spacing.dart';
import './reaction_banner_animations.dart';
import './reaction_renderers/reaction_renderers.dart';
import './speaking_indicator.dart';
import '../../core/providers/phase3_connection_points.dart' show voiceStateProvider;
import '../../services/voice/voice_service.dart' show VoiceState;

class AuraReactionBanner extends ConsumerStatefulWidget {
  const AuraReactionBanner({super.key});

  @override
  ConsumerState<AuraReactionBanner> createState() =>
      _AuraReactionBannerState();
}

class _AuraReactionBannerState extends ConsumerState<AuraReactionBanner>
    with SingleTickerProviderStateMixin {
  ReactionBannerAnimationController? _animCtrl;
  Reaction? _lastReaction;
  bool _animating = false;

  @override
  void initState() {
    super.initState();
    _setupAnimation();
  }

  void _setupAnimation() {
    _animCtrl = ReactionBannerAnimationController(
      vsync: this,
      durations: kDefaultBannerDurations,
      curves: kDefaultBannerCurves,
      onCompleted: () {
        if (mounted) {
          setState(() { _animating = false; });
          // Signal lifecycle completed → speech coordinator releases speech.
          ref.read(reactionLifecycleProvider).complete();
        }
      },
    );
  }

  @override
  void dispose() {
    _animCtrl?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final lifecycle = ref.watch(reactionLifecycleProvider);
    final currentReaction = ref.watch(currentReactionForBannerProvider);
    final isActive = ref.watch(isReactionBannerActiveProvider);

    // Detect new reaction → start animation.
    if (isActive && currentReaction != null && currentReaction != _lastReaction && !_animating) {
      _lastReaction = currentReaction;
      _animating = true;
      // Start entering → visible → exiting animation sequence.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _animCtrl?.play();
      });
    }

    // If not active, show speaking indicator or nothing.
    if (!isActive || currentReaction == null || !_animating) {
      return _buildSpeakingIndicatorIfNeeded(context);
    }

    // Build the animated banner.
    return AnimatedBuilder(
      animation: _animCtrl!.controller,
      builder: (context, child) {
        final slideOffset = _animCtrl!.slideOffsetAnimation.value;
        final opacity = _animCtrl!.opacityAnimation.value;

        return Positioned(
          top: MediaQuery.of(context).padding.top + 8,
          left: AppSpacing.md,
          right: AppSpacing.md,
          child: Opacity(
            opacity: opacity.clamp(0.0, 1.0),
            child: Transform.translate(
              offset: Offset(0, -60 * (1.0 - slideOffset)),
              child: child,
            ),
          ),
        );
      },
      child: _buildBannerContent(context, currentReaction),
    );
  }

  /// Builds the actual banner card content.
  Widget _buildBannerContent(BuildContext context, Reaction reaction) {
    final style = reaction.visualStyle ?? defaultStyleForType(reaction.type);
    final payload = reaction.payload;
    final textDirection = Directionality.of(context);

    return Directionality(
      textDirection: textDirection,
      child: Container(
        padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.md,
          vertical: AppSpacing.sm,
        ),
        decoration: BoxDecoration(
          color: const Color(0xFF0B0E14).withValues(alpha: 0.92),
          borderRadius: BorderRadius.circular(AppRadius.lg),
          border: Border.all(
            color: AppColors.cyan.withValues(alpha: 0.15),
            width: 1,
          ),
          boxShadow: [
            BoxShadow(
              color: AppColors.cyan.withValues(alpha: 0.08),
              blurRadius: 16,
              spreadRadius: 2,
            ),
          ],
        ),
        child: Row(
          textDirection: textDirection,
          children: [
            // Visual style renderer
            Expanded(
              child: _buildVisualStyleRenderer(style, payload),
            ),
            // Urgency indicator dot
            if (reaction.urgency != ReactionUrgency.normal) ...[
              const SizedBox(width: 8),
              _buildUrgencyDot(reaction.urgency),
            ],
          ],
        ),
      ),
    );
  }

  /// Routes to the correct visual style renderer.
  Widget _buildVisualStyleRenderer(
    ReactionVisualStyle style,
    Map<String, dynamic>? payload,
  ) {
    if (payload == null) {
      return const SizedBox.shrink();
    }

    return switch (style) {
      ReactionVisualStyle.emoji => ReactionEmojiRenderer(
          emoji: payload['emoji'] as String? ??
              (_lastReaction?.type is EmojiReactionType
                  ? (_lastReaction!.type as EmojiReactionType).emoji
                  : '✨'),
          label: payload['label'] as String?,
        ),
      ReactionVisualStyle.pixelArt => ReactionPixelArtRenderer(
          grid: _pixelGridFromPayload(payload),
          label: payload['label'] as String?,
          cellSize: (payload['cellSize'] as num?)?.toDouble() ?? 4.0,
        ),
      ReactionVisualStyle.asciiArt => ReactionAsciiArtRenderer(
          art: payload['art'] as String? ?? payload['text'] as String? ?? '',
          label: payload['label'] as String?,
        ),
      ReactionVisualStyle.code => ReactionCodeRenderer(
          code: payload['code'] as String? ?? '',
          language: payload['language'] as String?,
          label: payload['label'] as String?,
        ),
      ReactionVisualStyle.meme => ReactionMemeRenderer(
          topText: payload['topText'] as String? ?? payload['text'] as String? ?? '',
          bottomText: payload['bottomText'] as String?,
        ),
      ReactionVisualStyle.customVisual => ReactionCustomVisualRenderer(
          icon: Icons.star,
          title: payload['title'] as String?,
          body: payload['body'] as String? ?? payload['text'] as String? ?? '',
          accentColor: payload['accentColor'] != null
              ? Color(payload['accentColor'] as int)
              : null,
        ),
    };
  }

  /// Extracts a pixel grid from the payload.
  List<List<int>> _pixelGridFromPayload(Map<String, dynamic> payload) {
    final raw = payload['grid'];
    if (raw is List<List<int>>) return raw;
    if (raw is List) {
      return raw.map((row) {
        if (row is List<int>) return row;
        if (row is List) return row.cast<int>();
        return <int>[];
      }).toList();
    }
    // Default 5×5 grid.
    return List.generate(5, (_) => List.generate(5, (_) => 1));
  }

  /// Urgency indicator dot.
  Widget _buildUrgencyDot(ReactionUrgency urgency) {
    final color = switch (urgency) {
      ReactionUrgency.low => Colors.green,
      ReactionUrgency.normal => Colors.transparent,
      ReactionUrgency.high => Colors.orange,
      ReactionUrgency.critical => Colors.red,
    };
    return Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(
        color: color,
        shape: BoxShape.circle,
      ),
    );
  }

  /// Shows a speaking indicator when the banner has completed
  /// but speech is still pending/active.
  Widget _buildSpeakingIndicatorIfNeeded(BuildContext context) {
    final speechCoordinator = ref.read(reactionSpeechCoordinatorProvider);
    final voiceState = ref.watch(voiceStateProvider);
    final shouldShow = speechCoordinator.hasPendingSpeech || voiceState == VoiceState.speaking;
    if (shouldShow) {
      return Positioned(
        top: MediaQuery.of(context).padding.top + 12,
        left: 0,
        right: 0,
        child: Center(
          child: SpeakingIndicator(
            color: AppColors.cyan,
            barCount: 5,
            barHeight: 14,
            barWidth: 2.5,
          ),
        ),
      );
    }
    return const SizedBox.shrink();
  }
}
