package forge.util;

import forge.ImageKeys;
import forge.StaticData;
import forge.card.CardEdition;
import forge.gui.FThreads;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;

import java.io.File;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;

public abstract class ImageFetcher {
    // see https://scryfall.com/docs/api/languages and
    // https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes
    private static final HashMap<String, String> langCodeMap = new HashMap<>();
    protected static final boolean disableHostedDownload = true;
    private static final HashSet<String> fetching = new HashSet<>();

    static {
        langCodeMap.put("en-US", "en");
        langCodeMap.put("es-ES", "es");
        langCodeMap.put("fr-FR", "fr");
        langCodeMap.put("de-DE", "de");
        langCodeMap.put("it-IT", "it");
        langCodeMap.put("pt-BR", "pt");
        langCodeMap.put("ja-JP", "ja");
        langCodeMap.put("ko-KR", "ko");
        langCodeMap.put("ru-RU", "ru");
        langCodeMap.put("zh-CN", "zhs");
        langCodeMap.put("zh-HK", "zht");
    }

    private HashMap<String, HashSet<Callback>> currentFetches = new HashMap<>();

    private String getScryfallDownloadURL(PaperCard c, String face, boolean useArtCrop, boolean hasSetLookup, String imagePath, ArrayList<String> downloadUrls) {
        StaticData data = StaticData.instance();
        CardEdition edition = data.getEditions().get(c.getEdition());
        if (edition == null) // edition does not exist - some error occurred with card data
            return null;
        if (hasSetLookup) {
            List<PaperCard> clones = StaticData.instance().getCommonCards().getAllCards(c.getName());
            for (PaperCard pc : clones) {
                if (clones.size() > 1) {//clones only
                    if (!c.getEdition().equalsIgnoreCase(pc.getEdition())) {
                        CardEdition ed = data.getEditions().get(pc.getEdition());
                        if (ed != null) {
                            String setCode = ed.getScryfallCode();
                            String langCode = ed.getCardsLangCode();
                            downloadUrls.add(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + ImageUtil.getScryfallDownloadUrl(pc, face, setCode, langCode, useArtCrop));
                        }
                    }
                } else {// original from set
                    CardEdition ed = data.getEditions().get(pc.getEdition());
                    if (ed != null) {
                        String setCode = ed.getScryfallCode();
                        String langCode = ed.getCardsLangCode();
                        downloadUrls.add(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + ImageUtil.getScryfallDownloadUrl(pc, face, setCode, langCode, useArtCrop));
                    }
                }
            }
        } else {
            String setCode = edition.getScryfallCode();
            String langCode = edition.getCardsLangCode();
            String primaryUrl = ImageUtil.getScryfallDownloadUrl(c, face, setCode, langCode, useArtCrop);
            downloadUrls.add(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + primaryUrl);

            // It seems like the scryfall lookup might be better if we didn't include the language code at all?
            downloadUrls.add(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + ImageUtil.getScryfallDownloadUrl(c, face, setCode, "", useArtCrop));

            String alternateUrl = ImageUtil.getScryfallDownloadUrl(c, face, setCode, langCode, useArtCrop, true);
            if (alternateUrl != null && !alternateUrl.equals(primaryUrl)) {
                downloadUrls.add(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + alternateUrl);
                downloadUrls.add(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + ImageUtil.getScryfallDownloadUrl(c, face, setCode, "", useArtCrop, true));
            }
        }
        return null;
    }

    public static String getPlanechaseFilename(final String cardName) {
        return cardName.replace(" ", "_").replace("'", "")
                .replace("-", "").replace("!", "").replace(":", "") + ".jpg";
    }

    public void fetchImage(final String imageKey, final Callback callback) {
        FThreads.assertExecutedByEdt(true);

        if (FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_DISABLE_CARD_IMAGES))
            return;

        if (!FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER))
            return;

        // Fake card (like the ante prompt) trying to be "fetched"
        if (imageKey.length() < 2)
            return;
        if (imageKey.startsWith(ImageKeys.BOOSTER_PREFIX)) {
            final ArrayList<String> downloadUrls = new ArrayList<>();
            final String filename = imageKey.substring(ImageKeys.BOOSTER_PREFIX.length());
            // TODO Update image server or alternative hosting
            downloadUrls.add("https://downloads.cardforge.org/images/products/boosters/" + filename);
            System.out.println("Fetching from " + downloadUrls);


            FileUtil.ensureDirectoryExists(ForgeConstants.CACHE_BOOSTER_PICS_DIR);
            File destFile = new File(ForgeConstants.CACHE_BOOSTER_PICS_DIR, filename);
            System.out.println("Destination File " + destFile.getAbsolutePath() + " exists: " + destFile.exists());
            if (destFile.exists())
                return;
            setupObserver(destFile.getAbsolutePath(), callback, downloadUrls);
            return;
        }
        if (imageKey.equalsIgnoreCase("t:null"))
            return;

        //planechaseBG file...
        final ArrayList<String> downloadUrls = new ArrayList<>();
        if (imageKey.startsWith("PLANECHASEBG:")) {
            final String cardName = imageKey.substring("PLANECHASEBG:".length());
            PaperCard pc = StaticData.instance().getVariantCards().getCard(cardName);
            if (pc != null) {
                CardEdition ed = StaticData.instance().getEditions().get(pc.getEdition());
                if (ed != null) {
                    String setCode = ed.getScryfallCode();
                    String langCode = ed.getCardsLangCode();
                    downloadUrls.add("PLANECHASEBG:" + ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + ImageUtil.getScryfallDownloadUrl(pc, "", setCode, langCode, true));
                    FileUtil.ensureDirectoryExists(ForgeConstants.CACHE_PLANECHASE_PICS_DIR);
                    File destFile = new File(ForgeConstants.CACHE_PLANECHASE_PICS_DIR, getPlanechaseFilename(cardName));
                    if (destFile.exists())
                        return;

                    setupObserver(destFile.getAbsolutePath(), callback, downloadUrls);
                    return;
                }
            }
            return;
        }

        boolean useArtCrop = "Crop".equals(FModel.getPreferences().getPref(ForgePreferences.FPref.UI_CARD_ART_FORMAT));
        final String prefix = imageKey.substring(0, 2);
        File destFile = null;
        String face = "";
        if (prefix.equals(ImageKeys.CARD_PREFIX)) {
            PaperCard paperCard = ImageUtil.getPaperCardFromImageKey(imageKey);
            if (paperCard == null) {
                System.err.println("Paper card not found for: " + imageKey);
                return;
            }
            //Skip fetching if it's a custom user card.
            if (paperCard.getRules().isCustom()) {
                return;
            }
            // Skip fetching if artist info is not available for art crop
            if (useArtCrop && paperCard.getArtist().isEmpty())
                return;
            String imagePath = ImageUtil.getImageRelativePath(paperCard, "", true, false);
            final boolean hasSetLookup = ImageKeys.hasSetLookup(imagePath);
            if (imageKey.endsWith(ImageKeys.BACKFACE_POSTFIX)) {
                face = "back";
            } else if (imageKey.endsWith(ImageKeys.SPECFACE_W)) {
                face = "white";
            } else if (imageKey.endsWith(ImageKeys.SPECFACE_U)) {
                face = "blue";
            } else if (imageKey.endsWith(ImageKeys.SPECFACE_B)) {
                face = "black";
            } else if (imageKey.endsWith(ImageKeys.SPECFACE_R)) {
                face = "red";
            } else if (imageKey.endsWith(ImageKeys.SPECFACE_G)) {
                face = "green";
            }
            String filename = "";
            switch (face) {
                case "back":
                    filename = paperCard.getCardAltImageKey();
                    break;
                case "white":
                    filename = paperCard.getCardWSpecImageKey();
                    break;
                case "blue":
                    filename = paperCard.getCardUSpecImageKey();
                    break;
                case "black":
                    filename = paperCard.getCardBSpecImageKey();
                    break;
                case "red":
                    filename = paperCard.getCardRSpecImageKey();
                    break;
                case "green":
                    filename = paperCard.getCardGSpecImageKey();
                    break;
                default:
                    filename = paperCard.getCardImageKey();
                    break;
            }
            if (useArtCrop) {
                filename = TextUtil.fastReplace(filename, ".full", ".artcrop");
            }
            boolean updateLink = false;
            if ("back".equals(face)) {// seems getimage relative path don't process variants for back faces.
                try {
                    filename = TextUtil.fastReplace(filename, "1.full", imageKey.substring(imageKey.lastIndexOf('|') + 1, imageKey.indexOf('$')) + ".full");
                    updateLink = true;
                } catch (Exception e) {
                    filename = paperCard.getCardAltImageKey();
                }
            }
            destFile = new File(ForgeConstants.CACHE_CARD_PICS_DIR, filename + ".jpg");

            //skip ftp if using art crop
            if (!useArtCrop) {
                //move priority of ftp image here
                StringBuilder setDownload = new StringBuilder(ForgeConstants.URL_PIC_DOWNLOAD);
                if (!hasSetLookup) {
                    if (!updateLink) {
                        setDownload.append(ImageUtil.getDownloadUrl(paperCard, face));
                    } else {
                        String url = ImageUtil.getDownloadUrl(paperCard, face);
                        setDownload.append(TextUtil.fastReplace(url, "1.full", imageKey.substring(imageKey.lastIndexOf('|') + 1, imageKey.indexOf('$')) + ".full"));
                    }
                    downloadUrls.add(setDownload.toString());
                } else {
                    List<PaperCard> clones = StaticData.instance().getCommonCards().getAllCards(paperCard.getName());
                    for (PaperCard pc : clones) {
                        if (clones.size() > 1) {//clones only
                            if (!paperCard.getEdition().equalsIgnoreCase(pc.getEdition())) {
                                downloadUrls.add(ForgeConstants.URL_PIC_DOWNLOAD + ImageUtil.getDownloadUrl(pc, face));
                            }
                        } else {// original from set
                            downloadUrls.add(ForgeConstants.URL_PIC_DOWNLOAD + ImageUtil.getDownloadUrl(pc, face));
                        }
                    }
                }
            }
            final String cardCollectorNumber = paperCard.getCollectorNumber();
            if (!cardCollectorNumber.equals(IPaperCard.NO_COLLECTOR_NUMBER)) {
                // This function adds to downloadUrls for us
                this.getScryfallDownloadURL(paperCard, face, useArtCrop, hasSetLookup, filename, downloadUrls);
            }
        } else if (ImageKeys.getTokenKey(ImageKeys.HIDDEN_CARD).equals(imageKey)) {
            // extra logic for hidden card to not clog the other logic
            final String filename = "hidden.png";
            if (ImageKeys.missingCards.contains(filename))
                return;
            // scryfall only as png version
            downloadUrls.add("https://cards.scryfall.io/back.png");
            ImageKeys.missingCards.add(filename);
            destFile = new File(ForgeConstants.CACHE_TOKEN_PICS_DIR, filename);
        } else if (prefix.equals(ImageKeys.TOKEN_PREFIX)) {
            String tmp = imageKey;
            if (tmp.endsWith(ImageKeys.BACKFACE_POSTFIX)) {
                face = "back";
                tmp = tmp.substring(0, tmp.length() - ImageKeys.BACKFACE_POSTFIX.length());
            }
            String[] tempdata = tmp.substring(2).split("\\|"); // We want to check the edition first.
            String tokenName = tempdata[0];
            String setCode = tempdata.length > 1 ? tempdata[1] : CardEdition.UNKNOWN_CODE;

            StringBuilder sb = new StringBuilder();
            if (tempdata.length > 1) {
                sb.append(setCode).append("/");
            }
            if (tempdata.length > 2) {
                sb.append(tempdata[2]).append("_");
            }
            sb.append(tokenName);
            if (tempdata.length <= 2 && !face.isEmpty()) {
                sb.append("_").append(face);
            }
            sb.append(".jpg");

            final String filename = sb.toString();
            destFile = new File(ForgeConstants.CACHE_TOKEN_PICS_DIR, filename);
            if (ImageKeys.missingCards.contains(filename)
                    || filename.equalsIgnoreCase("null.jpg")
                    || destFile.exists())
                return;

            if (tempdata.length < 2) {
                if (!"planechase".equals(tempdata[0]))
                    System.err.println("Token image key is malformed: " + imageKey);
                ImageKeys.missingCards.add(filename);
                return;
            }

            // Load the paper token from filename + edition
            CardEdition edition = StaticData.instance().getEditions().get(setCode);
            if (edition == null || edition.getType() == CardEdition.Type.CUSTOM_SET)
                return; //Custom set token, skip fetching.

            //PaperToken pt = StaticData.instance().getAllTokens().getToken(tokenName, setCode);
            Collection<CardEdition.EditionEntry> allTokens = edition.getTokens().get(tokenName);

            if (tempdata.length > 2) {
                String tokenCode = edition.getTokensCode();
                String langCode = edition.getCardsLangCode();
                // just assume the CNr from the token image is valid
                downloadUrls.add(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + ImageUtil.getScryfallTokenDownloadUrl(tempdata[2], tokenCode, langCode, face));
            } else if (!allTokens.isEmpty()) {
                // This loop is going to try to download all the arts until it finds one
                // This is a bit wrong since it _should_ just be trying to get the one with the appropriate collector number
                // Since we don't have that for tokens, we'll just take the first one
                // Ideally we would have some mapping for generating card to determine which art indexed/collector number to try to fetch
                // Token art we're downloading and which location we're storing it in.
                // Once we're pulling from PaperTokens this section will change a bit
                Iterator<CardEdition.EditionEntry> it = allTokens.iterator();
                CardEdition.EditionEntry tis;
                while (it.hasNext()) {
                    tis = it.next();
                    String tokenCode = edition.getTokensCode();
                    String langCode = edition.getCardsLangCode();
                    if (tis.collectorNumber() == null || tis.collectorNumber().isEmpty()) {
                        continue;
                    }

                    downloadUrls.add(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + ImageUtil.getScryfallTokenDownloadUrl(tis.collectorNumber(), tokenCode, langCode, face));
                }
            }

            ImageKeys.missingCards.add(filename);
        }

        if (downloadUrls.isEmpty()) {
            System.err.println("No download URLs for: " + imageKey);
            if (destFile != null) {
                System.err.println("  You may put your own image in: " + destFile.getAbsolutePath());
            }
            return;
        }

        if (destFile.exists()) {
            // TODO: Figure out why this codepath gets reached.
            // Ideally, fetchImage() wouldn't be called if we already have the image.
            if (prefix.equals(ImageKeys.CARD_PREFIX)) {
                PaperCard paperCard = ImageUtil.getPaperCardFromImageKey(imageKey);
                if (paperCard != null)
                    paperCard.hasImage(true);
            }
            return;
        }

        setupObserver(destFile.getAbsolutePath(), callback, downloadUrls);
    }

    private void setupObserver(final String destPath, final Callback callback, final ArrayList<String> downloadUrls) {
        // Note: No synchronization is needed here because this is executed on
        // EDT thread (see assert on top) and so is the notification of observers.
        HashSet<Callback> observers = currentFetches.get(destPath);
        if (observers != null) {
            // Already in the queue, simply add the new observer.
            observers.add(callback);
            return;
        } else if (fetching.contains(destPath)) {
            // Already fetching, but somehow no observers?
            return;
        }

        observers = new HashSet<>();
        observers.add(callback);
        fetching.add(destPath);
        currentFetches.put(destPath, observers);

        final Runnable notifyObservers = () -> {
            FThreads.assertExecutedByEdt(true);

            for (Callback o : currentFetches.get(destPath)) {
                if (o != null)
                    o.onImageFetched();
            }
            currentFetches.remove(destPath);
        };
        try {
            ThreadUtil.getServicePool().submit(getDownloadTask(downloadUrls.toArray(new String[0]), destPath, notifyObservers));
        } catch (RejectedExecutionException re) {
            re.printStackTrace();
        }
    }

    protected abstract Runnable getDownloadTask(String[] toArray, String destPath, Runnable notifyObservers);

    public interface Callback {
        void onImageFetched();
    }
}
