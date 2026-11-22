import {loadStripe} from '@stripe/stripe-js/pure/index.js';

// The owned retry build and its manager share the same installed pure loader.
loadStripe.setLoadParameters({advancedFraudSignals:false});
