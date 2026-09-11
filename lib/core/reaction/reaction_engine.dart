import 'package:flutter_riverpod/flutter_riverpod.dart';

class ReactionState {
  final bool isAnalyzing;
  final String? lastReaction;

  const ReactionState({this.isAnalyzing = false, this.lastReaction});

  static const initial = ReactionState();

  ReactionState copyWith({bool? isAnalyzing, String? lastReaction}) {
    return ReactionState(
      isAnalyzing: isAnalyzing ?? this.isAnalyzing,
      lastReaction: lastReaction ?? this.lastReaction,
    );
  }
}

class ReactionEngine extends StateNotifier<ReactionState> {
  ReactionEngine() : super(ReactionState.initial);

  void updateState(ReactionState newState) {
    state = newState;
  }

  void reset() {
    state = ReactionState.initial;
  }

  @override
  void dispose() {
    super.dispose();
  }
}
