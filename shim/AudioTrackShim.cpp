/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * AudioTrack constructor shim for libsink-mtk (Wi-Fi Display sink).
 *
 * Android 17 added a trailing `const std::string& codecProvenance = ""`
 * parameter to the AudioTrack constructor and REMOVED the old 16-arg form.
 * The Android 16 blob libsink-mtk.so references the now-deleted symbol:
 *
 *   android::AudioTrack::AudioTrack(audio_stream_type_t, uint32_t,
 *       audio_format_t, audio_channel_mask_t, size_t, audio_output_flags_t,
 *       const wp<IAudioTrackCallback>&, int32_t, audio_session_t,
 *       transfer_type, const audio_offload_info_t*,
 *       const AttributionSourceState&, const audio_attributes_t*, bool, float,
 *       audio_port_handle_t)
 *
 * We cannot define this as a class method (the declaration no longer exists in
 * the class), so we define a free function whose assembler name IS the exact
 * mangled symbol of the old C1 (complete-object) constructor. Its first
 * parameter is the implicit `this`. Inside, we placement-new the object using
 * the real A17 constructor with an empty codecProvenance.
 */

#include <new>
#include <string>

#include <media/AudioTrack.h>

namespace android {

// The leading AudioTrack* is the implicit `this` pointer that the C1 ctor
// receives. The asm() label binds this free function to the exact mangled
// name of the removed 16-arg constructor.
extern "C" void __audiotrack_ctor_shim(
        AudioTrack* self,
        audio_stream_type_t streamType,
        uint32_t sampleRate,
        audio_format_t format,
        audio_channel_mask_t channelMask,
        size_t frameCount,
        audio_output_flags_t flags,
        const wp<AudioTrack::IAudioTrackCallback>& callback,
        int32_t notificationFrames,
        audio_session_t sessionId,
        AudioTrack::transfer_type transferType,
        const audio_offload_info_t* offloadInfo,
        const content::AttributionSourceState& attributionSource,
        const audio_attributes_t* pAttributes,
        bool doNotReconnect,
        float maxRequiredSpeed,
        audio_port_handle_t selectedDeviceId)
        asm("_ZN7android10AudioTrackC1E19audio_stream_type_tj14audio_format_t20audio_channel_mask_tm20audio_output_flags_tRKNS_2wpINS0_19IAudioTrackCallbackEEEi15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tRKNS_7content22AttributionSourceStateEPK18audio_attributes_tbfi");

extern "C" void __audiotrack_ctor_shim(
        AudioTrack* self,
        audio_stream_type_t streamType,
        uint32_t sampleRate,
        audio_format_t format,
        audio_channel_mask_t channelMask,
        size_t frameCount,
        audio_output_flags_t flags,
        const wp<AudioTrack::IAudioTrackCallback>& callback,
        int32_t notificationFrames,
        audio_session_t sessionId,
        AudioTrack::transfer_type transferType,
        const audio_offload_info_t* offloadInfo,
        const content::AttributionSourceState& attributionSource,
        const audio_attributes_t* pAttributes,
        bool doNotReconnect,
        float maxRequiredSpeed,
        audio_port_handle_t selectedDeviceId) {
    // Construct in place using the real A17 constructor (with empty
    // codecProvenance). Produces a fully-initialized AudioTrack at *self,
    // exactly as the original 16-arg constructor would have.
    new (self) AudioTrack(streamType,
                          sampleRate,
                          format,
                          channelMask,
                          frameCount,
                          flags,
                          callback,
                          notificationFrames,
                          sessionId,
                          transferType,
                          offloadInfo,
                          attributionSource,
                          pAttributes,
                          doNotReconnect,
                          maxRequiredSpeed,
                          selectedDeviceId,
                          /* codecProvenance */ std::string());
}

}  // namespace android