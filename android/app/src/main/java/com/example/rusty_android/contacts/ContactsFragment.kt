package com.example.rusty_android.contacts

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.NavigationUI
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.rusty_android.R
import com.example.rusty_android.database.ContactDatabase
import com.example.rusty_android.databinding.FragmentContactsBinding
import com.google.android.material.snackbar.Snackbar

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class ContactsFragment : Fragment() {

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {

        setHasOptionsMenu(true)

        // Get a reference to the binding object and inflate the fragment views.
        val binding: FragmentContactsBinding = DataBindingUtil.inflate(
            inflater, R.layout.fragment_contacts, container, false)

        val application = requireNotNull(this.activity).application

        val dataSource = ContactDatabase.getInstance(application).contactDatabaseDao

        val viewModelFactory = ContactsViewModelFactory(dataSource, application)

        // With ViewModelFactory
        val contactsViewModel = ViewModelProvider(this, viewModelFactory).get(ContactsViewModel::class.java)

        binding.contactsViewModel = contactsViewModel

        binding.lifecycleOwner = this

        // Add an Observer on the state variable for showing a Snackbar message
        // when the CLEAR button is pressed.
        contactsViewModel.showSnackBarEvent.observe(viewLifecycleOwner, Observer {
            if (it == true) { // Observed state is true.
                Snackbar.make(
                    requireActivity().findViewById(android.R.id.content),
                    getString(R.string.cleared_message),
                    Snackbar.LENGTH_SHORT // How long to display the message.
                ).show()
                // Reset state to make sure the snackbar is only shown once, even if the device
                // has a configuration change.
                contactsViewModel.doneShowingSnackbar()
            }
        })

        // Add an Observer on the state variable for Navigating when STOP button is pressed.
        contactsViewModel.navigateToChat.observe(viewLifecycleOwner, Observer { contact ->
            contact?.let {
                // We need to get the navController from this, because button is not ready, and it
                // just has to be a view. For some reason, this only matters if we hit stop again
                // after using the back button, not if we hit stop and choose a quality.
                // Also, in the Navigation Editor, for Quality -> Tracker, check "Inclusive" for
                // popping the stack to get the correct behavior if we press stop multiple times
                // followed by back.
                // Also: https://stackoverflow.com/questions/28929637/difference-and-uses-of-oncreate-oncreateview-and-onactivitycreated-in-fra
                this.findNavController().navigate(
                    ContactsFragmentDirections
                        .actionContactsFragmentToChatFragment(contact))
                // Reset state to make sure we only navigate once, even if the device
                // has a configuration change.
                contactsViewModel.doneNavigating()
            }
        })

        contactsViewModel.navigateToChat.observe(viewLifecycleOwner, Observer { contact ->
            contact?.let {

                this.findNavController().navigate(
                    ContactsFragmentDirections
                        .actionContactsFragmentToChatFragment(contact))
                contactsViewModel.onChatNavigated()
            }
        })

        val manager = LinearLayoutManager(activity)
        binding.contactList.layoutManager = manager

        val adapter = ContactsAdapter(ContactListener { contactId ->
            contactsViewModel.onMeshContactClicked(contactId)
        })

        binding.contactList.adapter = adapter

        contactsViewModel.contacts.observe(viewLifecycleOwner, Observer {
            it?.let {
                adapter.addHeaderAndSubmitList(it)
            }
        })

        return binding.root
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater?.inflate(R.menu.menu_main, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return NavigationUI.onNavDestinationSelected(item,
            requireView().findNavController())
                || super.onOptionsItemSelected(item)
    }
}
