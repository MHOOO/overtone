(ns overtone.sc.ugen.info)

(def specs
  [
   {:name "SampleRate"
    :rates #{:ir}
    :doc "returns the current sample rate"}

   {:name "SampleDur"
    :rates #{:ir}
    :doc "returns the current sample duration of the server in seconds"}

   {:name "RadiansPerSample"
    :rates #{:ir}
    :doc ""}

   {:name "ControlRate"
    :rates #{:ir}
    :doc "returns the current control rate of the server"}

   {:name "ControlDur"
    :rates #{:ir}
    :doc "returns the current block duration of the server in seconds"}

   {:name "SubsampleOffset"
    :rates #{:ir}
    :doc "offset from synth start within one sample"}

   {:name "NumOutputBuses"
    :rates #{:ir}
    :doc "returns the number of output buses allocated on the server"}

   {:name "NumInputBuses"
    :rates #{:ir}
    :doc "returns the number of output buses allocated on the server"}

   {:name "NumAudioBuses"
    :rates #{:ir}
    :doc "returns the number of audio buses allocated on the server"}

   {:name "NumControlBuses"
    :rates #{:ir}
    :doc "returns the number of control buses allocated on the server"}

   {:name "NumBuffers"
    :rates #{:ir}
    :doc "returns the number of buffers allocated on the server"}

   {:name "NumRunningSynths"
    :rates #{:ir :kr}
    :doc "returns the number of currently running synths"}

   {:name "BufSampleRate"
    :args [{:name "buf" :default 0}]
    :rates #{:kr :ir}
    :doc "returns the buffers current sample rate"}

   {:name "BufRateScale"
    :args [{:name "buf" :default 0}]
    :rates #{:kr :ir}
    :doc "returns a ratio by which the playback of a buffer is to be scaled"}

   {:name "BufFrames"
    :args [{:name "buf" :default 0}]
    :rates #{:kr :ir}
    :doc "returns the current number of allocated frames"}

   {:name "BufSamples"
    :args [{:name "buf" :default 0}]
    :rates #{:kr :ir}
    :doc "current number of samples allocated in the buffer"}

   {:name "BufDur"
    :args [{:name "buf" :default 0}]
    :rates #{:kr :ir}
    :doc "returns the current duration of a buffer"}

   {:name "BufChannels"
    :args [{:name "buf" :default 0}]
    :rates #{:kr :ir}
    :doc "current number of channels of soundfile in buffer"}

   {:name "CheckBadValues"
    :args [{:name "val"}]
    :rates #{:kr :ir}
    :doc "test for infinity, not-a-number, and denormals"}

   {:name "Poll"
    :args [{:name "trig" :default 0.0
            :doc "a non-positive to positive transition telling Poll to return a value"}
           {:name "in" :default 0.0
            :doc "the signal you want to poll"}
           {:name "label" :default "val: "
            :doc "a string or symbol to be printed with the polled value"}
           {:name "trigid" :default 0.0
            :doc "if greater then 0, a '/tr' message is sent back to the client (similar to SendTrig)"}]
    :rates #{:ar :kr}
    :doc "Print the current output value of a ugen.  (Returns its input value, so it is transparent to the signal path when debugging.)"}

])
