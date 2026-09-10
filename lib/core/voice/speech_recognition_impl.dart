import 'dart:async';

import 'package:speech_to_text/speech_to_text.dart' as stt;

import '../../services/voice/speech_recognition_service.dart';
import '../../core/errors/failures.dart';

/// Real [SpeechRecognitionService] implementation using speech_to_text package.
class SpeechRecognitionServiceImpl implements SpeechRecognitionService {
  SpeechRecognitionServiceImpl();

  final stt.SpeechToText _speech = stt.SpeechToText();
  bool _initialized = false;
  bool _isListening = false;
  String _lastRecognizedText = '';

  @override
  Future<bool> isAvailable() async {
    if (!_initialized) {
      _initialized = await _speech.initialize(
        onError: (_) {},
        onStatus: (_) {},
      );
    }
    return _initialized && _speech.hasError == false;
  }

  @override
  Future<void> startListening({
    required void Function(String text) onResult,
    String locale = 'ku',
  }) async {
    final available = await isAvailable();
    if (!available) {
      throw VoiceFailure(
        message: 'Speech recognition is not available on this device',
        code: 'STT_NOT_AVAILABLE',
      );
    }

    _lastRecognizedText = '';
    _isListening = true;

    await _speech.listen(
      onResult: (result) {
        _lastRecognizedText = result.recognizedWords;
        if (result.finalResult) {
          onResult(_lastRecognizedText);
        }
      },
      localeId: locale,
      listenMode: stt.ListenMode.confirmation,
      cancelOnError: true,
      partialResults: true,
    );
  }

  @override
  Future<void> stopListening() async {
    if (_isListening) {
      await _speech.stop();
      _isListening = false;
    }
  }

  @override
  bool get isListening => _isListening;
}
