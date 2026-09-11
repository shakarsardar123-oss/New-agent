import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';

class AuraAvatar extends LeafRenderObjectWidget {
  const AuraAvatar({super.key});

  @override
  RenderBox createRenderObject(BuildContext context) {
    return RenderProxyBox();
  }
}
