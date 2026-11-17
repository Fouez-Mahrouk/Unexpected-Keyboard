package juloo.keyboard2.suggestions;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.Config;
import juloo.keyboard2.ComposeKey;
import juloo.keyboard2.ComposeKeyData;

/** Keep track of the word being typed and provide suggestions for
    [CandidatesView]. */
public final class Suggestions
{
  Callback _callback;
  Config _config;
  boolean _enabled;

  /** Current suggestions. The best suggestion is at index [0]. */
  public String[] suggestions = new String[MAX_COUNT];
  /** Number of suggestions at the beginning of the [suggestions] array that
      are not [null]. */
  public int count = 0;
  public String emoji_suggestion = null;
  /** Number of suggestions in [suggestions]. */
  public static final int MAX_COUNT = 3;

  /** Automatic language detection. After each finished word, every installed
      dictionary is asked whether it knows the word. A word known by exactly
      one dictionary is a vote for that dictionary. When enough of the last
      words vote for the same dictionary, it becomes the current one. */
  Dictionaries _dictionaries = null;
  String _previous_word = "";
  final String[] _votes = new String[VOTE_WINDOW];
  int _vote_next = 0;
  String _known_dictionary = null;
  /** Number of recent words that are looked at. */
  static final int VOTE_WINDOW = 3;
  /** Votes needed to switch. */
  static final int VOTE_THRESHOLD = 2;
  /** Shorter words never vote. */
  static final int MIN_VOTE_WORD_LENGTH = 3;

  public Suggestions(Callback c, Config conf)
  {
    _callback = c;
    _config = conf;
  }

  /** Enable automatic language detection. */
  public void set_dictionaries(Dictionaries d)
  {
    _dictionaries = d;
  }

  public void started()
  {
    _enabled = _config.editor_config.should_show_candidates_view;
    _previous_word = "";
    clear();
  }

  public void currently_typed_word(String word)
  {
    if (!_enabled)
      return;
    detect_language(word);
    if (word.length() < 2 || _config.current_dictionary == null)
      clear();
    else
      query_suggestions(word);
    _callback.set_suggestions(this);
  }

  /** A word is finished when the typed word becomes empty. */
  void detect_language(String word)
  {
    String finished = _previous_word;
    _previous_word = word;
    if (_dictionaries == null || !word.isEmpty()
        || finished.length() < MIN_VOTE_WORD_LENGTH)
      return;
    // Forget old votes if the dictionary was changed by something else
    // (the language button, a layout switch).
    String current = _config.current_dictionary_name;
    if (!Objects.equals(current, _known_dictionary))
    {
      Arrays.fill(_votes, null);
      _known_dictionary = current;
    }
    _votes[_vote_next] = language_of_word(finished);
    _vote_next = (_vote_next + 1) % VOTE_WINDOW;
    String winner = vote_winner();
    if (winner != null && !winner.equals(current))
    {
      _dictionaries.set_current_dictionary(_config, winner);
      _known_dictionary = winner;
      Arrays.fill(_votes, null);
    }
  }

  /** The only installed dictionary that knows the word. [null] if no
      dictionary or several dictionaries know it. */
  String language_of_word(String word)
  {
    String w = apply_substitutions(word.toLowerCase());
    String found_in = null;
    for (String name : _dictionaries.get_installed())
    {
      Cdict[] dicts = _dictionaries.load(name);
      if (dicts == null)
        continue;
      Cdict main = Dictionaries.find_by_name(dicts, "main");
      if (main == null || !main.find(w).found)
        continue;
      if (found_in != null)
        return null;
      found_in = name;
    }
    return found_in;
  }

  /** The dictionary with enough votes, or [null]. */
  String vote_winner()
  {
    for (int i = 0; i < VOTE_WINDOW; i++)
    {
      String candidate = _votes[i];
      if (candidate == null)
        continue;
      int n = 0;
      for (int j = 0; j < VOTE_WINDOW; j++)
        if (candidate.equals(_votes[j]))
          n++;
      if (n >= VOTE_THRESHOLD)
        return candidate;
    }
    return null;
  }

  void clear()
  {
    count = 0;
    for (int i = 0; i < MAX_COUNT; i++)
      suggestions[i] = null;
    emoji_suggestion = null;
  }

  int query_suggestions(String word)
  {
    Cdict dict = _config.current_dictionary;
    boolean first_char_upper = Character.isUpperCase(word.charAt(0));
    word = apply_substitutions(word);
    Cdict.Result r = dict.find(word);
    int i = 0;
    if (r.found)
      suggestions[i++] = dict.word(r.index);
    int[] suffixes = dict.suffixes(r, MAX_COUNT);
    // Disable distance search for small words
    int[] dist = (word.length() < 3 || i + 1 >= MAX_COUNT) ? NO_RESULTS :
      dict.distance(word, 1, MAX_COUNT);
    for (int j = 0; j < MAX_COUNT && i < MAX_COUNT; j++)
    {
      if (suffixes.length > j)
        suggestions[i++] = dict.word(suffixes[j]);
      if (dist.length > j && i < MAX_COUNT)
        suggestions[i++] = dict.word(dist[j]);
    }
    if (first_char_upper)
      capitalize_results(suggestions, i);
    emoji_suggestion = query_emoji(word); // word with substitutions applied
    count = i;
    return i;
  }

  static void capitalize_results(String[] s, int count)
  {
    for (int i = 0; i < count; i++)
      s[i] = s[i].substring(0, 1).toUpperCase() + s[i].substring(1);
  }

  String query_emoji(String word)
  {
    Cdict dict = _config.emoji_dictionary;
    // Disable emoji suggestion for short words
    if (dict == null || word.length() < 3)
      return null;
    Cdict.Result r = dict.find(word);
    if (r.found)
      return dict.word(r.index);
    int[] s = dict.suffixes(r, 1);
    if (s.length > 0)
      return dict.word(s[0]);
    return null;
  }

  /** Apply the same substitutions that were used when building the
      dictionaries to find word aliases. This catches missing diacritics for
      example. */
  String apply_substitutions(String w)
  {
    StringBuilder b = new StringBuilder(w);
    int len = w.length();
    for (int i = 0; i < len; i++)
    {
      char r =
        ComposeKey.transform_char(ComposeKeyData.substitutions, b.charAt(i));
      if (r != 0) b.setCharAt(i, r);
    }
    return b.toString();
  }

  static final int[] NO_RESULTS = new int[0];

  public static interface Callback
  {
    public void set_suggestions(Suggestions suggestions);
  }
}
